/*----------------------------------------------------------------------------*/
/* Copyright (c) 2018 FIRST. All Rights Reserved.                             */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
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
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.highgui.HighGui;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.utils.Converters;

/*
   JSON format:
   {
       "team": <team number>,
       "ntmode": <"client" or "server", "client" if unspecified>
       "cameras": [
           {
               "name": <camera name>
               "path": <path, e.g. "/dev/video0">
               "pixel format": <"MJPEG", "YUYV", etc>   // optional
               "width": <video mode width>              // optional
               "height": <video mode height>            // optional
               "fps": <video mode fps>                  // optional
               "brightness": <percentage brightness>    // optional
               "white balance": <"auto", "hold", value> // optional
               "exposure": <"auto", "hold", value>      // optional
               "properties": [                          // optional
                   {
                       "name": <property name>
                       "value": <property value>
                   }
               ],
               "stream": {                              // optional
                   "properties": [
                       {
                           "name": <stream property name>
                           "value": <stream property value>
                       }
                   ]
               }
           }
       ]
   }
 */

 /**
  * Most of this code is stock from the Java example downloadable from frcvision.local.
  * What has been added is the ability to run this program on a desktop computer, and 
  * images are processed and output to an MJPEG stream. The readme in this project comes
  * from this example, and is a good starting point for understanding how to deploy this
  * code to the raspberry pi.
  */
public final class Main {
  private static String configFile = "/boot/frc.json";
  private static final String desktopModeFlag = "-desktop";

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
    // it is possible, but you'll have to turn down image resolution and frame rates,
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
   * This is the class which does the image processing. Everytime a frame is 
   * made available, the function process(mat) is called. In order to access
   * any data after the image is proccessed, it must be stored as a public 
   * member variable. See the Main method for more.
   */
  public static class MyPipeline implements VisionPipeline {
    public Mat bin = new Mat();
    public Mat hsv = new Mat();
    public Mat out = new Mat();
    public int val = 0;

    public int hMin = 86;
    public int sMin = 60;
    public int vMin = 64;

    public int hMax = 114;
    public int sMax = 255;
    public int vMax = 255;

    @Override
    public void process(Mat mat) {
      Timer timer = new Timer();
      
      // convert our image to grayscale to ensure OpenCV is modifying images
      //mgproc.cvtColor(mat, out, Imgproc.COLOR_BGR2GRAY);

      
      // convert our RGB image to HSV in order to thresh hold it by color (hue) and intensity (saturation/value)
      timer.start();
      Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_BGR2HSV);
      timer.stop();
      print("Converting from BGR to HSV", timer);

      timer.start();
      Core.inRange(hsv, new Scalar(hMin, sMin, vMin), new Scalar(hMax, sMax, vMax), bin);
      timer.stop();
      print("Thresholding HSV", timer);

      timer.start();
      
      out = bin;
    }
  }

  /**
   * Main.
   */
  public static void main(String... args) {
    boolean desktopMode = false;
    if (args.length > 0) {
      desktopMode = (args[0].equals(Main.desktopModeFlag));
    }

    if (desktopMode) {
      System.out.println("Debugging!");

      System.load("C:\\Users\\Joshua\\opencv344\\opencv\\build\\java\\x64\\opencv_java344.dll");

      Mat image = Imgcodecs.imread("C:\\Users\\Joshua\\Pictures\\P-20161108-00861_HiRes-JPEG-24bit-RGB-News.jpg");
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

    // creates an MJPEG server for our output images, but we still have to give it images to output
    CvSource output = CameraServer.getInstance().putVideo("Proc", 640, 480);

    // start image processing on camera 0 if present
    if (cameras.size() >= 1) {
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new MyPipeline(), pipeline -> {
              
              // give our output MJPEG server our processed image 
              output.putFrame(pipeline.out);
      });
      /* something like this for GRIP:
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new GripPipeline(), pipeline -> {
        ...
      });
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

      SwingUtilities.invokeLater(new Runnable()
      {
        public void run()
        {
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
    //image formatting
    Imgcodecs.imencode(".jpg", imgContainer,byteMatData);
    // Convert to array
    byte[] byteArray = byteMatData.toArray();
    BufferedImage img= null;
    try {
        InputStream in = new ByteArrayInputStream(byteArray);
        //load image
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
  
}
