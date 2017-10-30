/*
 * TODO: Copyright (C) 2017 LEIDOS.
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

package gov.dot.fhwa.saxton.carma.guidance;

import cav_msgs.*;
import cav_srvs.GetDriversWithCapabilities;
import cav_srvs.GetDriversWithCapabilitiesRequest;
import cav_srvs.GetDriversWithCapabilitiesResponse;
import geometry_msgs.AccelStamped;
import geometry_msgs.TwistStamped;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.IPubSubService;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.IPublisher;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.IService;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.ISubscriber;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.OnMessageCallback;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.OnServiceResponseCallback;

import org.jboss.netty.buffer.ChannelBuffers;
import org.ros.exception.RosRuntimeException;
import org.ros.exception.ServiceNotFoundException;
import org.ros.node.ConnectedNode;
import org.ros.node.parameter.ParameterTree;

import sensor_msgs.NavSatFix;
import std_msgs.Float64;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guidance package Tracking component
 * <p>
 * Reponsible for detecting when the vehicle strays from it's intended route or
 * trajectory and signalling the failure on the /system_alert topic
 */
public class Tracking extends GuidanceComponent {
	// Member variables
	protected final long sleepDurationMillis = 100; // Frequency for J2735
	private int msgCount = 0;
	private float vehicleWidth = 0;
	private float vehicleLength = 0;
	private boolean drivers_ready = false;
	private boolean steer_wheel_ready = false;
	private boolean nav_sat_fix_ready = false;
	private boolean heading_ready = false;
	private boolean velocity_ready = false;
	private boolean brake_ready = false;
	private boolean transmission_ready = false;
	private boolean acceleration_ready = false;
	protected GuidanceExceptionHandler exceptionHandler;
	private Random randomIdGenerator = new Random();
	private byte[] random_id = new byte[4];
	private IPublisher<BSM> bsmPublisher;
	private ISubscriber<AccelStamped> accelerationSubscriber;
	private ISubscriber<NavSatFix> navSatFixSubscriber;
	private ISubscriber<HeadingStamped> headingStampedSubscriber;
	private ISubscriber<TwistStamped> velocitySubscriber;
	private ISubscriber<Float64> steeringWheelSubscriber;
	private ISubscriber<Float64> brakeSubscriber;
	private ISubscriber<TransmissionState> transmissionSubscriber;
	private IService<GetDriversWithCapabilitiesRequest, GetDriversWithCapabilitiesResponse> getDriversWithCapabilitiesClient;
	private List<String> req_drivers = Arrays.asList("steering_wheel_angle", "brake_position", "transmission_state");
	private List<String> resp_drivers;

	public Tracking(AtomicReference<GuidanceState> state, IPubSubService pubSubService, ConnectedNode node) {
		super(state, pubSubService, node);
		this.exceptionHandler = new GuidanceExceptionHandler(state, log);
	}

	@Override
	public String getComponentName() {
		return "Guidance.Tracking";
	}

	@Override
	public void onGuidanceStartup() {
		
		try {
			// Publishers
			bsmPublisher = pubSubService.getPublisherForTopic("bsm", BSM._TYPE);

			// Subscribers
			navSatFixSubscriber = pubSubService.getSubscriberForTopic("nav_sat_fix", NavSatFix._TYPE);
			headingStampedSubscriber = pubSubService.getSubscriberForTopic("heading", HeadingStamped._TYPE);
			velocitySubscriber = pubSubService.getSubscriberForTopic("velocity", TwistStamped._TYPE);
			accelerationSubscriber = pubSubService.getSubscriberForTopic("acceleration_set", AccelerationSet4Way._TYPE);
			
			if(bsmPublisher == null 
					|| navSatFixSubscriber == null 
					|| headingStampedSubscriber == null 
					|| velocitySubscriber == null 
					|| accelerationSubscriber == null) {
				log.warn("Tracking cannot initialize pubs and subs");
			}
			
			navSatFixSubscriber.registerOnMessageCallback(new OnMessageCallback<NavSatFix>() {
				@Override
				public void onMessage(NavSatFix msg) {
					nav_sat_fix_ready = true;
				}
			});
			
			
			headingStampedSubscriber.registerOnMessageCallback(new OnMessageCallback<HeadingStamped>() {
				@Override
				public void onMessage(HeadingStamped msg) {
					heading_ready = true;
				}
			});
			
			
			velocitySubscriber.registerOnMessageCallback(new OnMessageCallback<TwistStamped>() {
				@Override
				public void onMessage(TwistStamped msg) {
					velocity_ready = true;
				}
			});
			
			accelerationSubscriber.registerOnMessageCallback(new OnMessageCallback<AccelStamped>() {
				@Override
				public void onMessage(AccelStamped msg) {
					acceleration_ready = true;
				}
			});
			
		} catch (Exception e) {
			handleException(e);
		}
		
	}

	@Override
	public void onSystemReady() {
		
		// Make service call to get drivers
		try {
			log.info("Tracking is trying to get get_drivers_with_capabilities service...");
			getDriversWithCapabilitiesClient = pubSubService.getServiceForTopic("get_drivers_with_capabilities", GetDriversWithCapabilities._TYPE);
			if(getDriversWithCapabilitiesClient == null) {
				log.warn("get_drivers_with_capabilities service can not be found");
			}
			
			GetDriversWithCapabilitiesRequest driver_request_wrapper = getDriversWithCapabilitiesClient.newMessage();
			driver_request_wrapper.setCapabilities(req_drivers);
			getDriversWithCapabilitiesClient.callSync(driver_request_wrapper, new OnServiceResponseCallback<GetDriversWithCapabilitiesResponse>() {
				
				@Override
				public void onSuccess(GetDriversWithCapabilitiesResponse msg) {
					resp_drivers = msg.getDriverData();
					log.info("Tracking: service call is successful: " + resp_drivers);
				}
				
				@Override
				public void onFailure(Exception e) {
					throw new RosRuntimeException(e);				
				}
				
			});
			
			if(resp_drivers != null) {
				for(String driver_url : resp_drivers) {
					if(driver_url.endsWith("/can/steering_wheel_angle")) {
						steeringWheelSubscriber = pubSubService.getSubscriberForTopic(driver_url, Float64._TYPE);
						continue;
					}
					if(driver_url.endsWith("/can/brake_position")) {
						brakeSubscriber = pubSubService.getSubscriberForTopic(driver_url, Float64._TYPE);
						continue;
					}
					if(driver_url.endsWith("/can/transmission_state")) {
						transmissionSubscriber = pubSubService.getSubscriberForTopic(driver_url, TransmissionState._TYPE);
					}
				}
			} else {
				log.warn("Tracking: cannot find suitable drivers");
			}
			
			if(steeringWheelSubscriber == null || brakeSubscriber == null || transmissionSubscriber == null) {
				log.warn("Tracking: initialize subs failed");
			}
			
			steeringWheelSubscriber.registerOnMessageCallback(new OnMessageCallback<Float64>() {
				@Override
				public void onMessage(Float64 msg) {
					steer_wheel_ready = true;
				}
			});
			
			brakeSubscriber.registerOnMessageCallback(new OnMessageCallback<Float64>() {
				@Override
				public void onMessage(Float64 msg) {
					brake_ready = true;
				}
			});
			
			transmissionSubscriber.registerOnMessageCallback(new OnMessageCallback<TransmissionState>() {
				@Override
				public void onMessage(TransmissionState msg) {
					transmission_ready = true;
				}
			});
			
		} catch (Exception e) {
			handleException(e);
		}
		
		drivers_ready = true;
	}

	@Override
	public void onGuidanceEnable() {
	}

	@Override
	public void loop() throws InterruptedException {
		
		if(drivers_ready) {
			try {
				log.info("Tracking nav_sat_fix subscribers status: " + nav_sat_fix_ready);
				log.info("Tracking steer_wheel subscribers status: " + steer_wheel_ready);
				log.info("Tracking heading subscribers status: " + heading_ready);
				log.info("Tracking velocity subscribers status: " + velocity_ready);
				log.info("Guidance.Tracking is publishing bsm...");
				bsmPublisher.publish(composeBSMData());
			} catch (Exception e) {
				handleException(e);
			}
		}
		
		Thread.sleep(sleepDurationMillis);
		
	}

	private BSM composeBSMData() {

		BSM bsmFrame = bsmPublisher.newMessage();

		try {
			// Set header
			bsmFrame.getHeader().setStamp(node.getCurrentTime());
			bsmFrame.getHeader().setFrameId("MessageNode");

			// Set core data
			BSMCoreData coreData = bsmFrame.getCoreData();
			coreData.setMsgCount((byte) (msgCount % 127));

			// ID is random and changes every 5 minutes
			if (msgCount == 0) {
				randomIdGenerator.nextBytes(random_id);
			} else if (msgCount == 3000) {
				msgCount = 0;
			}
			coreData.setId(ChannelBuffers.copiedBuffer(ByteOrder.LITTLE_ENDIAN, random_id));

			// Set GPS data
			coreData.setLatitude(90.0000001); // Default value when unknown
			coreData.setLongitude(180.0000001);
			coreData.setElev((float) -409.6);
			coreData.getAccuracy().setSemiMajor((float) (255 * 0.05));
			coreData.getAccuracy().setSemiMinor((float) (255 * 0.05));
			coreData.getAccuracy().setOrientation(65535 * 0.0054932479);
			if(nav_sat_fix_ready) {
				double lat = navSatFixSubscriber.getLastMessage().getLatitude();
				double Lon = navSatFixSubscriber.getLastMessage().getLongitude();
				float elev = (float) navSatFixSubscriber.getLastMessage().getAltitude();
				float semi_major = (float) navSatFixSubscriber.getLastMessage().getPositionCovariance()[0];
				float semi_minor = (float) navSatFixSubscriber.getLastMessage().getPositionCovariance()[4];
				double orientation = 0; //orientation of semi_major axis
				if(lat >= -90 && lat <= 90) {
					coreData.setLatitude(lat);
				}
				if(Lon >= -179.9999999 && Lon <= 180) {
					coreData.setLongitude(Lon);
				}
				if(elev >= -409.5 && elev <= 6143.9) {
					coreData.setElev(elev);
				}
				if(semi_major >= 0 && Math.sqrt(semi_major) <= 12.7) {
					coreData.getAccuracy().setSemiMajor((float) Math.sqrt(semi_major));
				}
				if(semi_minor >= 0 && Math.sqrt(semi_minor) <= 12.7) {
					coreData.getAccuracy().setSemiMinor((float) Math.sqrt(semi_minor));
				}
				if(orientation >= 0 && orientation <= 359.9945078786) {
					coreData.getAccuracy().setOrientation(orientation);
				}
			}
			
			// Set transmission state
			coreData.getTransmission().setTransmissionState((byte) 7);
			if(transmission_ready) {
				TransmissionState transmission_state = transmissionSubscriber.getLastMessage();
				if(transmission_state.getTransmissionState() >= 0 && transmission_state.getTransmissionState() < 7) {
					coreData.getTransmission().setTransmissionState(transmission_state.getTransmissionState());
				}
			}

			coreData.setSpeed((float) (8191 * 0.02));
			if(velocity_ready) {
				float speed = (float) velocitySubscriber.getLastMessage().getTwist().getLinear().getX();
				if(speed >= 0 && speed <= 163.8) {
					coreData.setSpeed(speed);
				}
			}
			
			coreData.setHeading(360);
			if(heading_ready) {
				float heading = (float) headingStampedSubscriber.getLastMessage().getHeading();
				if(heading >= 0 && heading <= 359.9875) {
					coreData.setHeading(heading);
				}
			}
			
			coreData.setAngle((float) 190.5);
			if(steer_wheel_ready) {
				float angle = (float) steeringWheelSubscriber.getLastMessage().getData();
				if(angle >= -189 && angle <= 189) {
					coreData.setAngle(angle);
				}
			}

			// N/A for now
			coreData.getAccelSet().setLongitudinal((float) (2001 * 0.01));
			coreData.getAccelSet().setLateral((float) (2001 * 0.01));
			coreData.getAccelSet().setVert((float) (-127 * 0.02 * 9.8));
			coreData.getAccelSet().setYawRate(0);
			
			coreData.getBrakes().getWheelBrakes().setBrakeAppliedStatus((byte) 16);
			if(brake_ready) {
				if(brakeSubscriber.getLastMessage().getData() >= 0) {
					coreData.getBrakes().getWheelBrakes().setBrakeAppliedStatus((byte) 15);
				} else {
					coreData.getBrakes().getWheelBrakes().setBrakeAppliedStatus((byte) 0);
				}
			}
			
			// N/A for now
			coreData.getBrakes().getTraction().setTractionControlStatus((byte) 0);
			coreData.getBrakes().getAbs().setAntiLockBrakeStatus((byte) 0);
			coreData.getBrakes().getScs().setStabilityControlStatus((byte) 0);
			coreData.getBrakes().getBrakeBoost().setBrakeBoostApplied((byte) 0);
			coreData.getBrakes().getAuxBrakes().setAuxiliaryBrakeStatus((byte) 0);

			// Set length and width only for the first time
			if (vehicleLength == 0 && vehicleWidth == 0) {
				ParameterTree param = node.getParameterTree();
				vehicleLength = (float) param.getDouble("~vehicle_length");
				vehicleWidth = (float) param.getDouble("~vehicle_width");
			}
			coreData.getSize().setVehicleLength(vehicleLength);
			coreData.getSize().setVehicleWidth(vehicleWidth);
			
			// Use ros node time
			coreData.setSecMark((short) ((node.getCurrentTime().toSeconds() * 1000) % 65535));

		} catch (Exception e) {
			handleException(e);
		}
		return bsmFrame;
	}

	protected void handleException(Exception e) {
		log.error("Tracking throws an exception...");
		exceptionHandler.handleException(e);
	}
}
