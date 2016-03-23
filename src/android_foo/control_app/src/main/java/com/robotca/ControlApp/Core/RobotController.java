package com.robotca.ControlApp.Core;

import android.content.Context;
import android.location.Location;
import android.preference.PreferenceManager;
import android.util.Log;

import com.robotca.ControlApp.Core.Plans.RobotPlan;
import com.robotca.ControlApp.R;

import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer;
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider;
import org.ros.message.MessageListener;
import org.ros.namespace.GraphName;
import org.ros.node.ConnectedNode;
import org.ros.node.Node;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMain;
import org.ros.node.NodeMainExecutor;
import org.ros.node.topic.Publisher;
import org.ros.node.topic.Subscriber;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import geometry_msgs.Point;
import geometry_msgs.Pose;
import geometry_msgs.Quaternion;
import geometry_msgs.Twist;
import nav_msgs.Odometry;
import sensor_msgs.LaserScan;
import sensor_msgs.NavSatFix;

/**
 * Created by Michael Brunson on 2/13/16.
 */
public class RobotController implements NodeMain {

    private final Context context;
    private Timer publisherTimer;
    private boolean publishVelocity;

    private Publisher<Twist> movePublisher;
    private Twist currentVelocityCommand;

    private Subscriber<NavSatFix> navSatFixSubscriber;
    private NavSatFix navSatFix;
    private final Object navSatFixMutex = new Object();

    private Subscriber<LaserScan> laserScanSubscriber;
    private LaserScan laserScan;
    private final Object laserScanMutex = new Object();

    private Subscriber<Odometry> odometrySubscriber;
    private Odometry odometry;
    private final Object odometryMutex = new Object();

    private Subscriber<Pose> poseSubscriber;
    private Pose pose;
    private final Object poseMutex = new Object();

    private RobotPlan motionPlan;
    private ConnectedNode connectedNode;

    // Listeners
    private ArrayList<MessageListener<LaserScan>> laserScanListeners;
    private ArrayList<MessageListener<Odometry>> odometryListeners;
    private ArrayList<MessageListener<NavSatFix>> navSatListeners;

    /**
     * LocationProvider subscribers can register to to receive location updates.
     */
    public final LocationProvider LOCATION_PROVIDER;

    // Current Robot's Pose information
    private static Point startPos, currentPos;
    private static Quaternion rotation;

    /**
     * Creates a RobotController.
     * @param context The Context the RobotController belongs to.
     */
    public RobotController(Context context) {
        this.context = context;

        this.laserScanListeners = new ArrayList<>();
        this.odometryListeners = new ArrayList<>();
        this.navSatListeners = new ArrayList<>();

        this.LOCATION_PROVIDER = new LocationProvider();
        this.addNavSatFixListener(this.LOCATION_PROVIDER);
    }

    /**
     * Adds an Odometry listener.
     * @param l The listener
     */
    public void addOdometryListener(MessageListener<Odometry> l) {
        odometryListeners.add(l);
    }

    /**
     * Adds a NavSatFix listener.
     * @param l The listener
     */
    public void addNavSatFixListener(MessageListener<NavSatFix> l) {
        navSatListeners.add(l);
    }

    /**
     * Adds a NavSatFix listener.
     * @param l The listener
     */
    public void addLaserScanListener(MessageListener<LaserScan> l) {
        laserScanListeners.add(l);
    }

    public void initialize(NodeMainExecutor nodeMainExecutor, NodeConfiguration nodeConfiguration) {
        nodeMainExecutor.execute(this, nodeConfiguration.setNodeName("android/robot_controller"));
    }

    public void runPlan(RobotPlan plan) {
        stop();

        publishVelocity = true;
        motionPlan = plan;
        motionPlan.run(this);
    }

    public void stop() {
        if (motionPlan != null) {
            motionPlan.stop();
            motionPlan = null;
        }

        publishVelocity = false;
        publishVelocity(0, 0, 0);

        if(movePublisher != null){
            movePublisher.publish(currentVelocityCommand);
        }
    }

    public void publishVelocity(double linearVelocityX, double linearVelocityY,
                                double angularVelocityZ) {
        if (currentVelocityCommand != null) {
            currentVelocityCommand.getLinear().setX(linearVelocityX);
            currentVelocityCommand.getLinear().setY(-linearVelocityY);
            currentVelocityCommand.getLinear().setZ(0);
            currentVelocityCommand.getAngular().setX(0);
            currentVelocityCommand.getAngular().setY(0);
            currentVelocityCommand.getAngular().setZ(-angularVelocityZ);
        } else {
            Log.w("Emergency Stop", "currentVelocityCommand is null");
        }
    }

    public void forceVelocity(double linearVelocityX, double linearVelocityY,
                              double angularVelocityZ) {
        publishVelocity = true;
        publishVelocity(linearVelocityX, linearVelocityY, angularVelocityZ);
    }

    @Override
    public GraphName getDefaultNodeName() {
        return GraphName.of("android/robot_controller");
    }

    @Override
    public void onStart(ConnectedNode connectedNode) {
        this.connectedNode = connectedNode;
        update();
    }

    public void update() {
        if(this.connectedNode != null) {
            shutdownTopics();

            String moveTopic = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("edittext_joystick_topic", context.getString(R.string.joy_topic));
            String navSatTopic = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("edittext_navsat_topic", context.getString(R.string.navsat_topic));
            String laserScanTopic = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("edittext_laser_scan_topic", context.getString(R.string.laser_scan_topic));
            String odometryTopic = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("edittext_odometry_topic", context.getString(R.string.odometry_topic));
            String poseTopic = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("edittext_pose_topic", context.getString(R.string.pose_topic));

            movePublisher = connectedNode.newPublisher(moveTopic, Twist._TYPE);
            currentVelocityCommand = movePublisher.newMessage();

            publisherTimer = new Timer();
            publisherTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (publishVelocity) {
                        movePublisher.publish(currentVelocityCommand);
                    }
                }
            }, 0, 80);
            publishVelocity = false;

            navSatFixSubscriber = connectedNode.newSubscriber(navSatTopic, NavSatFix._TYPE);
            navSatFixSubscriber.addMessageListener(new MessageListener<NavSatFix>() {
                @Override
                public void onNewMessage(NavSatFix navSatFix) {
                    setNavSatFix(navSatFix);
                }
            });

            laserScanSubscriber = connectedNode.newSubscriber(laserScanTopic, LaserScan._TYPE);
            laserScanSubscriber.addMessageListener(new MessageListener<LaserScan>() {
                @Override
                public void onNewMessage(LaserScan laserScan) {
                    setLaserScan(laserScan);
                }
            });

            odometrySubscriber = connectedNode.newSubscriber(odometryTopic, Odometry._TYPE);
            odometrySubscriber.addMessageListener(new MessageListener<Odometry>() {
                @Override
                public void onNewMessage(Odometry odometry) {
                    setOdometry(odometry);
                }
            });

            poseSubscriber = connectedNode.newSubscriber(poseTopic, Pose._TYPE);
            poseSubscriber.addMessageListener(new MessageListener<Pose>() {
                @Override
                public void onNewMessage(Pose pose) {
                    setPose(pose);
                }
            });
        }
    }

    private void shutdownTopics() {
        if(publisherTimer != null) {
            publisherTimer.cancel();
        }

        if (movePublisher != null) {
            movePublisher.shutdown();
        }

        if (navSatFixSubscriber != null) {
            navSatFixSubscriber.shutdown();
        }

        if (laserScanSubscriber != null) {
            laserScanSubscriber.shutdown();
        }

        if(odometrySubscriber != null){
            odometrySubscriber.shutdown();
        }

        if(poseSubscriber != null){
            poseSubscriber.shutdown();
        }
    }

    @Override
    public void onShutdown(Node node) {
        shutdownTopics();
    }

    @Override
    public void onShutdownComplete(Node node) {
        this.connectedNode = null;
    }

    @Override
    public void onError(Node node, Throwable throwable) {

    }

    public LaserScan getLaserScan() {
        synchronized (laserScanMutex) {
            return laserScan;
        }
    }

    protected void setLaserScan(LaserScan laserScan) {
        synchronized (laserScanMutex) {
            this.laserScan = laserScan;
        }

        // Call the listener callbacks
        for (MessageListener<LaserScan> listener: laserScanListeners) {
            listener.onNewMessage(laserScan);
        }
    }

    public NavSatFix getNavSatFix() {
        synchronized (navSatFixMutex) {
            return navSatFix;
        }
    }

    protected void setNavSatFix(NavSatFix navSatFix) {
        synchronized (navSatFixMutex) {
            this.navSatFix = navSatFix;

            // Call the listener callbacks
            for (MessageListener<NavSatFix> listener: navSatListeners) {
                listener.onNewMessage(navSatFix);
            }
        }
    }

    public Odometry getOdometry() {
        synchronized (odometryMutex) {
            return odometry;
        }
    }

    protected void setOdometry(Odometry odometry) {
        synchronized (odometryMutex) {
            this.odometry = odometry;

            // Call the listener callbacks
            for (MessageListener<Odometry> listener: odometryListeners) {
                listener.onNewMessage(odometry);
            }

            // Record position TODO this should be moved to setPose() but that's not being called for some reason
            if (startPos == null) {
                startPos = odometry.getPose().getPose().getPosition();
            } else {
                currentPos = odometry.getPose().getPose().getPosition();
            }
            rotation = odometry.getPose().getPose().getOrientation();
        }
    }

    public Pose getPose() {
        synchronized (poseMutex) {
            return pose;
        }
    }

    public void setPose(Pose pose){
        synchronized (poseMutex){
            this.pose = pose;
        }

        Log.d("RobotController", "Pose Set");
//        // Record position
//        if (startPos == null) {
//            startPos = pose.getPosition();
//        } else {
//            currentPos = pose.getPosition();
//        }
//        rotation = pose.getOrientation();
    }

    /**
     * @return The Robot's x position
     */
    public static double getX() {
        if (currentPos == null)
            return 0.0;
        else
            return currentPos.getX() - startPos.getX();
    }

    /**
     * @return The Robot's y position
     */
    public static double getY() {
        if (currentPos == null)
            return 0.0;
        else
            return currentPos.getY() - startPos.getY();
    }

    /**
     * @return The Robot's heading in radians
     */
    public static double getHeading() {
        if (rotation == null)
            return 0.0;
        else
            return Utils.getHeading(org.ros.rosjava_geometry.Quaternion.fromQuaternionMessage(rotation));
    }
}