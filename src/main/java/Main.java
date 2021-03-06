
/*----------------------------------------------------------------------------*/
/* Copyright (c) 2018 FIRST. All Rights Reserved. */
/* Open Source Software - may be modified and shared by FRC teams. The code */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project. */
/*----------------------------------------------------------------------------*/

import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.wpi.cscore.CvSource;
import edu.wpi.cscore.MjpegServer;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.cscore.VideoSource;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.vision.VisionPipeline;
import edu.wpi.first.vision.VisionThread;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.core.Point;

/*
 * JSON format: { "team": <team number>, "ntmode": <"client" or "server", "client" if unspecified>
 * "cameras": [ { "name": <camera name> "path": <path, e.g. "/dev/video0"> "pixel format": <"MJPEG",
 * "YUYV", etc> // optional "width": <video mode width> // optional "height": <video mode height> //
 * optional "fps": <video mode fps> // optional "brightness": <percentage brightness> // optional
 * "white balance": <"auto", "hold", value> // optional "exposure": <"auto", "hold", value> //
 * optional "properties": [ // optional { "name": <property name> "value": <property value> } ],
 * "stream": { // optional "properties": [ { "name": <stream property name> "value": <stream
 * property value> } ] } } ] }
 */

/**
 * Most of this code is stock from the Java example downloadable from frcvision.local. What has been
 * added is the ability to run this program on a desktop computer, and images are processed and
 * output to an MJPEG stream. The readme in this project comes from this example, and is a good
 * starting point for understanding how to deploy this code to the raspberry pi.
 */
public final class Main {
  private static String configFile = "/boot/frc.json";
  private static final String desktopModeFlag = "-desktop";
  private static final String imageFolderFlag = "-images";
  private static final String openCvLibEnVar = "OPENCV_LIBRARY";

  @SuppressWarnings("MemberName")

  public static class CameraConfig {
    public String name;
    public String path;
    public JsonObject config;
    public JsonElement streamConfig;
  }

  public static int team;
  public static boolean server;
  public static List<CameraConfig> cameraConfigs = new ArrayList<>();

  private Main() {
  }

  /**
   * Report parse error.
   */
  public static void parseError(String str) {
    System.err.println("config error in '" + configFile + "': " + str);
  }

  /**
   * Read single camera configuration.
   */
  public static boolean readCameraConfig(JsonObject config) {
    CameraConfig cam = new CameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement pathElement = config.get("path");
    if (pathElement == null) {
      parseError("camera '" + cam.name + "': could not read path");
      return false;
    }
    cam.path = pathElement.getAsString();

    // stream properties
    cam.streamConfig = config.get("stream");

    cam.config = config;

    cameraConfigs.add(cam);
    return true;
  }

  /**
   * Read configuration file.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  public static boolean readConfig() {
    // parse file
    JsonElement top;
    try {
      top = new JsonParser().parse(Files.newBufferedReader(Paths.get(configFile)));
    } catch (IOException ex) {
      System.err.println("could not open '" + configFile + "': " + ex);
      return false;
    }

    // top level must be an object
    if (!top.isJsonObject()) {
      parseError("must be JSON object");
      return false;
    }
    JsonObject obj = top.getAsJsonObject();

    // team number
    JsonElement teamElement = obj.get("team");
    if (teamElement == null) {
      parseError("could not read team number");
      return false;
    }
    team = teamElement.getAsInt();

    // ntmode (optional)
    if (obj.has("ntmode")) {
      String str = obj.get("ntmode").getAsString();
      if ("client".equalsIgnoreCase(str)) {
        server = false;
      } else if ("server".equalsIgnoreCase(str)) {
        server = true;
      } else {
        parseError("could not understand ntmode value '" + str + "'");
      }
    }

    // cameras
    JsonElement camerasElement = obj.get("cameras");
    if (camerasElement == null) {
      parseError("could not read cameras");
      return false;
    }
    JsonArray cameras = camerasElement.getAsJsonArray();
    for (JsonElement camera : cameras) {
      if (!readCameraConfig(camera.getAsJsonObject())) {
        return false;
      }
    }

    return true;
  }

  /**
   * Start running the camera.
   */
  public static VideoSource startCamera(CameraConfig config) {
    System.out.println("Starting camera '" + config.name + "' on " + config.path);
    CameraServer inst = CameraServer.getInstance();
    UsbCamera camera = new UsbCamera(config.name, config.path);

    // Uncomment the two lines below if you want unproccessed images publishished.
    // it is unadvisable to stream both unprocessed and processed images
    // at the same time because of the ~4 megabit data cap when connected to FMS.
    // it is possible, but you'll have to turn down image resolution and frame
    // rates,
    // which may impact effectiveness of the vision program and introduce greater
    // delays between when an image is captured and when the robot code recieves
    // relevant data. This screws with control loops on the roborio.

    MjpegServer server = inst.startAutomaticCapture(camera);

    Gson gson = new GsonBuilder().create();

    camera.setConfigJson(gson.toJson(config.config));
    camera.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);

    if (config.streamConfig != null) {
      server.setConfigJson(gson.toJson(config.streamConfig));
    }

    return camera;
  }

  /**
   * This is the class which does the image processing. Everytime a frame is made available, the
   * function process(mat) is called. In order to access any data after the image is proccessed, it
   * must be stored as a public member variable. See the Main method for more.
   */
  public static class MyPipeline implements VisionPipeline {
    // note that field of view changes based on aspect ratio
    // published for logitech camera is 60 in 16:9, which
    // is 45 in 4:3
    public double FOV = 60;
    public double width = 432;
    public double height = 240;

    public Mat bin = new Mat();
    public Mat hsv = new Mat();
    public Mat out = new Mat();
    public int val = 0;

    public int hMin = 29; // 50
    public int sMin = 90; // 140
    public int vMin = 60; // 140

    public int hMax = 100; // 95
    public int sMax = 255; // 255
    public int vMax = 255; // 255

    // target angle bounds
    public int tPosUp = 64; // negative upper bound
    public int tPosLow = 43; // negative lower bound
    public int tNegUp = -8; // positive upper bound
    public int tNegLow = -81; // positive lower bound

    public int contourAreaMin = 90;
    public double robotHeading;
    public double ratioMin = 2.2;
    public double ratioMax = 4;

    @Override
    public void process(Mat mat) {
      if (!debugMode) {
        robotHeading = SmartDashboard.getNumber("heading", 0);
      }

      // we grab the robot's heading before
      Timer timer = new Timer();

      // convert our RGB image to HSV
      timer.start();
      Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_BGR2HSV);
      timer.stop();
      // print("Converting from BGR to HSV", timer);

      // Threshold based on Hue (color), Saturation, and Value
      // color is the most important identifier, but we also want pixels
      // that are the brightest
      timer.start();
      Core.inRange(hsv, new Scalar(hMin, sMin, vMin), new Scalar(hMax, sMax, vMax), bin);
      timer.stop();
      // print("Thresholding HSV", timer);

      // perform opening Morphological transformation to remove any small noise
      // that may have entered into the binary image.
      // see :
      // https://docs.opencv.org/3.4/d9/d61/tutorial_py_morphological_ops.html
      // You can change the parameters of kernal to 'tune' its effects
      // timer.start();
      Mat kernel = Mat.ones(5, 5, CvType.CV_8SC1);
      Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel);
      timer.stop();
      // print("Morph opening", timer);

      timer.start();
      List<MatOfPoint> binContours = new ArrayList<>();
      Imgproc.findContours(bin, binContours, new Mat(), Imgproc.RETR_LIST,
          Imgproc.CHAIN_APPROX_SIMPLE);
      timer.stop();
      // print("Finding contours", timer);

      List<MatOfPoint> filteredContours = new ArrayList<>();
      List<BetterRectangle> allRectanglesThatMayBePartOfATargetPair = new ArrayList<>();
      List<Pair> targets = new ArrayList<>();

      // Loop through each contour, removing contours that don't match our
      // "description" of a target
      for (int i = 0; i < binContours.size(); i++) {
        MatOfPoint contour = binContours.get(i); // current contour

        // filter out contours that are too small
        double contourArea = Imgproc.contourArea(contour);
        // System.out.println("Area: " + contourArea);
        if (contourArea < contourAreaMin) {
          // System.out.println("Removing contour for area");
          continue;
        }

        // Get the "Rotated Rectangle" representation of our contour
        timer.start();
        RotatedRect rectangle = Imgproc.minAreaRect(new MatOfPoint2f(contour.toArray()));
        timer.stop();
        // print("Min area rect", timer);

        // filter out rectangles that are at the incorrect tilt
        // not sure what reference frame this is using, but are
        // experimentally produced numbers
        if ((rectangle.angle < tNegLow || rectangle.angle > tNegUp)
            && (rectangle.angle < tPosLow || rectangle.angle > tPosUp)) {
          // System.out.println("Removing contour for angle");
          continue;
        }

        // convert contour into a MatOfPoint again, so it's easier to work with
        BetterRectangle betRect = new BetterRectangle(rectangle);

        // sampled height / width: = 2.777, 2.91, 2.57, 2.55, 3.77

        double ratio = betRect.height / betRect.width;
        // System.out.println("Ratio: " + ratio);
        if (ratio > ratioMax || ratio < ratioMin) {
          // System.out.println("Removing contour for ratio");
          continue;
        }

        allRectanglesThatMayBePartOfATargetPair.add(betRect);
      }

      // infer where the center of the target is
      if (allRectanglesThatMayBePartOfATargetPair.size() == 1) {
        BetterRectangle loneWolf = allRectanglesThatMayBePartOfATargetPair.get(0);

        double width = loneWolf.width;
        double expectedDistance = 5.5 * width;
        double centerx;
        double angle;
        // slanted right
        if (loneWolf.angle < -45) {
          angle = -15;
          centerx = loneWolf.rotatedRectangle.center.x + (expectedDistance);
        } else {
          angle = 15;
          centerx = loneWolf.rotatedRectangle.center.x - (expectedDistance);
        }

        RotatedRect fake = new RotatedRect(new Point(centerx, loneWolf.rotatedRectangle.center.y),
            new Size(loneWolf.width, loneWolf.height), angle);
        BetterRectangle imaginary = new BetterRectangle(fake);
        Pair p;
        if (angle == -75) {
          p = new Pair(imaginary, loneWolf);
        } else {
          p = new Pair(loneWolf, imaginary);
        }
        targets.add(p);
      } else {
        // we have more than one rectangle target thing
        // check each potential half target against every other, creating
        // a pair when we find two close enough together and haven't used
        // either half target in a previous pair
        boolean[] taken = new boolean[allRectanglesThatMayBePartOfATargetPair.size()];
        for (int i = 0; i < allRectanglesThatMayBePartOfATargetPair.size(); i++) {
          BetterRectangle halfTarget = allRectanglesThatMayBePartOfATargetPair.get(i);
          if (taken[i] == true) {
            continue;
          }
          for (int j = 0; j < allRectanglesThatMayBePartOfATargetPair.size(); j++) {

            BetterRectangle possibleMatch = allRectanglesThatMayBePartOfATargetPair.get(j);

            if (taken[j] == true) {
              continue;
            }
            if (possibleMatch == halfTarget) {
              continue;
            }

            // we expect a pair to have differing angles
            if (Math.abs(Math.abs(halfTarget.angle) - Math.abs(possibleMatch.angle)) < 13) {
              // System.out.println("halfTarget.angle: " + halfTarget.angle);
              // System.out.println("possibleMatch.angle: " + possibleMatch.angle);
              // System.out.println("Angle difference: "
              // + Math.abs((Math.abs(halfTarget.angle) - Math.abs(possibleMatch.angle))));
              // System.out.println("Removing for angle sameness");
              continue;
            }

            double width1 = halfTarget.width;
            double width2 = possibleMatch.width;

            // we expect our two half targets to be similar in width
            if (Math.abs(width1 - width2) > 50) {
              // System.out.println("Removing for width difference");
              continue;
            }

            double avgWidth = (width1 + width2) / 2.0;
            double distance = Math.abs(
                halfTarget.rotatedRectangle.center.x - possibleMatch.rotatedRectangle.center.x);

            double distToWidthRatio = distance / avgWidth;

            if (distToWidthRatio > 6 || distToWidthRatio < 4) {
              // System.out.println("Targets too close or too far apart!");
              continue;
            }

            double posDif =
                halfTarget.rotatedRectangle.center.x - possibleMatch.rotatedRectangle.center.x;

            if (posDif < 0 && halfTarget.angle < possibleMatch.angle) {
              // the contours are in order from right to left
              // and form a pair
              targets.add(new Pair(halfTarget, possibleMatch));
            } else if (posDif > 0 && halfTarget.angle > possibleMatch.angle) {
              // the contours are in order from left to right
              // and form a pair
              targets.add(new Pair(possibleMatch, halfTarget));
            }

            taken[i] = true;
            taken[j] = true;
          }
        }
      }

      double smallestAngle = Double.MAX_VALUE;
      for (Pair t : targets) {
        // find center of the target
        double leftx = t.left.rotatedRectangle.center.x;
        double lefty = t.left.rotatedRectangle.center.y;
        double rightx = t.right.rotatedRectangle.center.x;
        double righty = t.right.rotatedRectangle.center.y;
        double centerx = (leftx + rightx) / 2.0;
        double centery = (lefty + righty) / 2.0;
        Imgproc.circle(mat, new Point(centerx, centery), 3, new Scalar(0, 0, 255), -1);

        double imageCenterx = (width / 2);


        // negative means to the left of center
        // diff angle
        // ----- == ------
        // width FOV / 2
        double diff = centerx - imageCenterx;
        double angleDiff = (diff / width) * FOV / 2;
        // System.out.println("Angle: " + angleDiff);

        if (angleDiff < smallestAngle) {
          smallestAngle = angleDiff;
        }

        filteredContours.add(t.left.matOfPoint);
        filteredContours.add(t.right.matOfPoint);
      }

      if (!debugMode) {
        if (smallestAngle != Double.MAX_VALUE) {
          robotHeading = robotHeading + smallestAngle;
          SmartDashboard.putBoolean("target_found", true);
        } else {
          SmartDashboard.putBoolean("target_found", false);
        }
        SmartDashboard.putNumber("target_angle", robotHeading);
      }

      Imgproc.drawContours(mat, filteredContours, -1, new Scalar(0, 0, 255), 2);
      out = mat;
    }
  }

  public static boolean debugMode = false;

  /**
   * Main.
   */
  public static void main(String... args) {
    boolean desktopMode = false;
    String imageFolderPath = null;

    for (int i = 0; i < args.length; i++) {
      String arg = args[i];

      if (arg.equals(Main.desktopModeFlag)) {
        desktopMode = true;
        debugMode = true;
      } else if (arg.equals(Main.imageFolderFlag) && i + 1 < args.length) {
        imageFolderPath = args[i + 1];
      }
    }

    if (desktopMode) {
      System.out.println("Desktop Mode Enabled");

      String libraryPath = System.getenv(openCvLibEnVar);

      if (libraryPath == null) {
        throw new RuntimeException("\"" + openCvLibEnVar + "\" is not in Path");
      }
      System.load(libraryPath);

      Mat image = Imgcodecs.imread(imageFolderPath);
      showImage(image, "Input");

      MyPipeline pipeline = new MyPipeline();
      pipeline.process(image);
      showImage(pipeline.out, "Output");

      return;
    }

    if (args.length > 0) {
      configFile = args[0];
    }

    // read configuration
    if (!readConfig()) {
      return;
    }

    // start NetworkTables
    NetworkTableInstance ntinst = NetworkTableInstance.getDefault();
    if (server) {
      System.out.println("Setting up NetworkTables server");
      ntinst.startServer();
    } else {
      System.out.println("Setting up NetworkTables client for team " + team);
      ntinst.startClientTeam(team);
    }

    // start cameras
    List<VideoSource> cameras = new ArrayList<>();
    for (CameraConfig cameraConfig : cameraConfigs) {
      cameras.add(startCamera(cameraConfig));
    }

    // creates an MJPEG server for our output images, but we still have to give it
    // images to
    // output
    CvSource output = CameraServer.getInstance().putVideo("Proc", 432, 240);
    CvSource bin = CameraServer.getInstance().putVideo("Bin", 432, 240);

    // start image processing on camera 0 if present
    if (cameras.size() >= 1) {
      VisionThread visionThread = new VisionThread(cameras.get(0), new MyPipeline(), pipeline -> {

        // give our output MJPEG server our processed image
        output.putFrame(pipeline.out);
        bin.putFrame(pipeline.bin);
      });
      /*
       * something like this for GRIP: VisionThread visionThread = new VisionThread(cameras.get(0),
       * new GripPipeline(), pipeline -> { ... });
       */
      visionThread.start();
    }

    // loop forever
    for (;;) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException ex) {
        return;
      }
    }
  }

  private static void showImage(Mat image, String windowName) {
    BufferedImage display = ConvertMat2Image(image);

    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        JFrame editorFrame = new JFrame(windowName);
        editorFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        ImageIcon imageIcon = new ImageIcon(display);
        JLabel jLabel = new JLabel();
        jLabel.setIcon(imageIcon);
        editorFrame.getContentPane().add(jLabel, BorderLayout.CENTER);

        editorFrame.pack();
        editorFrame.setLocationRelativeTo(null);
        editorFrame.setVisible(true);
      }
    });
  }

  private static BufferedImage ConvertMat2Image(Mat imgContainer) {
    MatOfByte byteMatData = new MatOfByte();
    // image formatting
    Imgcodecs.imencode(".jpg", imgContainer, byteMatData);
    // Convert to array
    byte[] byteArray = byteMatData.toArray();
    BufferedImage img = null;
    try {
      InputStream in = new ByteArrayInputStream(byteArray);
      // load image
      img = ImageIO.read(in);
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
    return img;
  }

  public static void print(String message, Timer t) {
    System.out.println(message + " took " + t.getElapsed() + " ms");
  }

  private static class Timer {
    private long startTime;
    private long endTime;

    void start() {
      startTime = System.nanoTime();
    }

    void stop() {
      endTime = System.nanoTime();
    }

    double getElapsed() {
      return (endTime - startTime) / 1e6;
    }
  }

  private static class BetterRectangle {
    public RotatedRect rotatedRectangle;
    public MatOfPoint matOfPoint;
    public double width;
    public double height;
    public double area;
    public double angle;

    public BetterRectangle(RotatedRect rectangle) {
      rotatedRectangle = rectangle;
      angle = rotatedRectangle.angle;

      Point[] vertices = new Point[4];
      rectangle.points(vertices);

      Point p1 = vertices[0];
      Point p2 = vertices[1];
      Point p3 = vertices[2];
      Point p4 = vertices[3];

      if (rectangle.size.height > rectangle.size.width) {
        width = rectangle.size.width;
        height = rectangle.size.height;
      } else {
        width = rectangle.size.height;
        height = rectangle.size.width;
      }

      area = width * height;

      matOfPoint = new MatOfPoint(p1, p2, p3, p4);
    }
  }

  private static class Pair {
    public BetterRectangle left = null;
    public BetterRectangle right = null;

    public Pair(BetterRectangle l, BetterRectangle r) {
      left = l;
      right = r;
    }
  }

}
