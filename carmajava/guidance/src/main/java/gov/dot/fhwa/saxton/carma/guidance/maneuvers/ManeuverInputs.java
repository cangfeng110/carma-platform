/*
 * Copyright (C) 2018 LEIDOS.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package gov.dot.fhwa.saxton.carma.guidance.maneuvers;

import cav_msgs.ExternalObject;
import cav_msgs.ExternalObjectList;
import cav_msgs.RoadwayEnvironment;
import cav_msgs.RouteState;
import cav_msgs.RoadwayObstacle;
import com.google.common.util.concurrent.AtomicDouble;
import geometry_msgs.TwistStamped;
import gov.dot.fhwa.saxton.carma.guidance.GuidanceAction;
import gov.dot.fhwa.saxton.carma.guidance.GuidanceComponent;
import gov.dot.fhwa.saxton.carma.guidance.GuidanceState;
import gov.dot.fhwa.saxton.carma.guidance.GuidanceStateMachine;
import gov.dot.fhwa.saxton.carma.guidance.IStateChangeListener;
import gov.dot.fhwa.saxton.carma.guidance.params.RosParameterSource;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.IPubSubService;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.ISubscriber;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.OnMessageCallback;
import gov.dot.fhwa.saxton.carma.guidance.signals.Deadband;
import gov.dot.fhwa.saxton.carma.guidance.signals.MovingAverageFilter;
import gov.dot.fhwa.saxton.carma.guidance.signals.PidController;
import gov.dot.fhwa.saxton.carma.guidance.signals.Pipeline;
import gov.dot.fhwa.saxton.carma.guidance.util.ILogger;
import gov.dot.fhwa.saxton.carma.guidance.util.LoggerManager;
import org.ros.exception.RosRuntimeException;
import org.ros.node.ConnectedNode;

/**
 * Interacts with the ROS network to provide the necessary input data to the Maneuver classes, allowing the rest
 * of the classes in this package to remain ignorant of ROS.
 */
public class ManeuverInputs extends GuidanceComponent implements IManeuverInputs, IStateChangeListener {

    protected ISubscriber<RouteState> routeStateSubscriber_;
    protected ISubscriber<TwistStamped> twistSubscriber_;
    protected ISubscriber<RoadwayEnvironment> roadwaySubscriber_;
    protected double distanceDowntrack_ = 0.0; // m
    protected double currentSpeed_ = 0.0; // m/s
    protected double responseLag_ = 0.0; // sec
    protected int currentLane_ = 0;
    protected double vehicleLength_ = 3.0; // m
    protected AtomicDouble frontVehicleDistance = new AtomicDouble(IAccStrategy.NO_FRONT_VEHICLE_DISTANCE);
    protected AtomicDouble frontVehicleSpeed = new AtomicDouble(IAccStrategy.NO_FRONT_VEHICLE_SPEED);
    protected ILogger log;

    public ManeuverInputs(GuidanceStateMachine stateMachine, IPubSubService iPubSubService, ConnectedNode node) {
        super(stateMachine, iPubSubService, node);
        jobQueue.add(this::onStartup);
        stateMachine.registerStateChangeListener(this);
        log = LoggerManager.getLogger();
    }

    @Override
    public void onStartup() {
        // Setup the ACC Strategy factory for use by maneuvers
        double maxAccel = node.getParameterTree().getDouble("~vehicle_acceleration_limit", 2.5);
        double vehicleResponseLag = node.getParameterTree().getDouble("~vehicle_response_lag", 1.4);
        double desiredTimeGap = node.getParameterTree().getDouble("~desired_acc_timegap", 1.0);
        double minStandoffDistance = node.getParameterTree().getDouble("~min_acc_standoff_distance", 5.0);
        double exitDistanceFactor = node.getParameterTree().getDouble("~acc_exit_distance_factor", 1.5);
        double Kp = node.getParameterTree().getDouble("~acc_Kp", 1.0);
        double Ki = node.getParameterTree().getDouble("~acc_Ki", 0.0);
        double Kd = node.getParameterTree().getDouble("~acc_Kd", 0.0);
        log.info(String.format("ACC params Kp=%.02f, Ki=%.02f, Kd= %.02f, gap=%.02f, standoff=%.02f, exit_factor=%.02f", Kp, Ki, Kd,
                desiredTimeGap, minStandoffDistance, exitDistanceFactor));
        double deadband = node.getParameterTree().getDouble("~acc_pid_deadband", 0.0);
        int numSamples = node.getParameterTree().getInteger("~acc_number_of_averaging_samples", 1);
        vehicleLength_ = node.getParameterTree().getDouble("/saxton_cav/vehicle_length", vehicleLength_);

        PidController timeGapController = new PidController(Kp, Ki, Kd, desiredTimeGap);
        MovingAverageFilter movingAverageFilter = new MovingAverageFilter(numSamples);
        Deadband deadbandFilter = new Deadband(desiredTimeGap, deadband);

        Pipeline<Double> accFilterPipeline = new Pipeline<>(deadbandFilter, timeGapController, movingAverageFilter);
        BasicAccStrategyFactory accFactory = new BasicAccStrategyFactory(desiredTimeGap, maxAccel, vehicleResponseLag,
                minStandoffDistance, exitDistanceFactor, accFilterPipeline);
        AccStrategyManager.setAccStrategyFactory(accFactory);

        // subscribers
        routeStateSubscriber_ = pubSubService.getSubscriberForTopic("route_state", RouteState._TYPE);
        routeStateSubscriber_.registerOnMessageCallback(new OnMessageCallback<RouteState>() {
            @Override
            public void onMessage(RouteState msg) {
                distanceDowntrack_ = msg.getDownTrack();
                currentLane_ = msg.getLaneIndex();
            }
        });

        twistSubscriber_ = pubSubService.getSubscriberForTopic("velocity", TwistStamped._TYPE);
        twistSubscriber_.registerOnMessageCallback(new OnMessageCallback<TwistStamped>() {
            @Override
            public void onMessage(TwistStamped msg) {
                currentSpeed_ = msg.getTwist().getLinear().getX();
            }
        });

        /*
         * Presently this subscriber will be mapped by saxton_cav.launch directly to the
         * front long range object topic provided by the Radar Driver, bypassing sensor
         * fusion and the interface manager. This subscriber depends on sensor frame
         * data (rather than odom frame data) to accurately derive distance values to
         * nearby vehicles. Pending full implementation of Sensor Fusion and Roadway
         * modules it is recommended to change this to depend on whatever vehicle frame
         * data is available in those data publications.
         */

        roadwaySubscriber_ = pubSubService.getSubscriberForTopic("roadway_environment", RoadwayEnvironment._TYPE);
        roadwaySubscriber_.registerOnMessageCallback(new OnMessageCallback<RoadwayEnvironment>() {
            @Override
            public void onMessage(RoadwayEnvironment msg) {
                double closestDistance = Double.POSITIVE_INFINITY;
                RoadwayObstacle frontVehicle = null;
                for (RoadwayObstacle obs : msg.getRoadwayObstacles()) {

                    // TODO This modification to downtrack values calculation should really be based on transforms. 
                    double frontVehicleDist = 0.5 * vehicleLength_ + obs.getDownTrack() - obs.getObject().getSize().getX() - distanceDowntrack_;
                    boolean inLane = obs.getPrimaryLane() == currentLane_;
                    // TODO: Add back into to check against the secondary lanes of an object
                    // byte[] secondaryLanes = obs.getSecondaryLanes().array();
                    // // Check secondary lanes
                    // for(int i = 0; i < secondaryLanes.length; i++) {
                    //     if (inLane)
                    //         break;
                    //     inLane = secondaryLanes[i] == currentLane_;
                    // }
                    
                    if (inLane && frontVehicleDist < closestDistance && frontVehicleDist > -0.0) {
                        // If it's closer than our previous best candidate, update our candidate
                        frontVehicle = obs;
                        closestDistance = frontVehicleDist;
                    }
                }

                // Store our results
                if (frontVehicle != null) {
                    frontVehicleDistance.set(frontVehicle.getObject().getPose().getPose().getPosition().getX());
                    frontVehicleSpeed.set(currentSpeed_ + frontVehicle.getObject().getVelocity().getTwist().getLinear().getX());
                } else {
                    frontVehicleDistance.set(IAccStrategy.NO_FRONT_VEHICLE_DISTANCE);
                    frontVehicleSpeed.set(IAccStrategy.NO_FRONT_VEHICLE_SPEED);
                }
            }
        });

        // parameters
        RosParameterSource params = new RosParameterSource(node.getParameterTree());
        responseLag_ = params.getDouble("~vehicle_response_lag");

        currentState.set(GuidanceState.STARTUP);
    }

    @Override
    public void onSystemReady() {
        currentState.set(GuidanceState.DRIVERS_READY);
    }

    @Override
    public void onActive() {
        currentState.set(GuidanceState.ACTIVE);
    }

    @Override
    public void onDeactivate() {
        currentState.set(GuidanceState.INACTIVE);
    }

    @Override
    public void onEngaged() {
        currentState.set(GuidanceState.ENGAGED);
    }

    @Override
    public void onCleanRestart() {
        currentState.set(GuidanceState.DRIVERS_READY);
        distanceDowntrack_ = 0.0;
        currentSpeed_ = 0.0;
        frontVehicleDistance.set(IAccStrategy.NO_FRONT_VEHICLE_DISTANCE);
        frontVehicleSpeed.set(IAccStrategy.NO_FRONT_VEHICLE_SPEED);
    }

    @Override
    public String getComponentName() {
        return "Guidance.Maneuvers.ManeuverInputs";
    }

    @Override
    public double getDistanceFromRouteStart() {
        return distanceDowntrack_;
    }

    @Override
    public double getCurrentSpeed() {
        return currentSpeed_;
    }

    @Override
    public double getResponseLag() {
        return responseLag_;
    }

    @Override
    public double getDistanceToFrontVehicle() {
        return frontVehicleDistance.get();
    }

    @Override
    public double getFrontVehicleSpeed() {
        return frontVehicleSpeed.get();
    }

    @Override
    public void onStateChange(GuidanceAction action) {
        log.debug("GUIDANCE_STATE", getComponentName() + " received action: " + action);
        switch (action) {
        case INTIALIZE:
            jobQueue.add(this::onSystemReady);
            break;
        case ACTIVATE:
            jobQueue.add(this::onActive);
            break;
        case DEACTIVATE:
            jobQueue.add(this::onDeactivate);
            break;
        case ENGAGE:
            jobQueue.add(this::onEngaged);
            break;
        case SHUTDOWN:
            jobQueue.add(this::onShutdown);
            break;
        case PANIC_SHUTDOWN:
            jobQueue.add(this::onPanic);
            break;
        case RESTART:
            jobQueue.add(this::onCleanRestart);
            break;
        default:
            throw new RosRuntimeException(
                    getComponentName() + "received unknown instruction from guidance state machine.");
        }
    }

    @Override
    public int getCurrentLane() {
        return currentLane_;
    }
}
