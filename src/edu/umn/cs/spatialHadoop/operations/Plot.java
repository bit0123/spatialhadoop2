/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the
 * NOTICE file distributed with this work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package edu.umn.cs.spatialHadoop.operations;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.FileOutputCommitter;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobContext;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.util.GenericOptionsParser;

import edu.umn.cs.spatialHadoop.ImageOutputFormat;
import edu.umn.cs.spatialHadoop.ImageWritable;
import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.SimpleGraphics;
import edu.umn.cs.spatialHadoop.core.CellInfo;
import edu.umn.cs.spatialHadoop.core.GridInfo;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.Shape;
import edu.umn.cs.spatialHadoop.core.SpatialSite;
import edu.umn.cs.spatialHadoop.mapred.BlockFilter;
import edu.umn.cs.spatialHadoop.mapred.ShapeArrayInputFormat;
import edu.umn.cs.spatialHadoop.mapred.ShapeInputFormat;
import edu.umn.cs.spatialHadoop.mapred.ShapeRecordReader;
import edu.umn.cs.spatialHadoop.mapred.TextOutputFormat;
import edu.umn.cs.spatialHadoop.nasa.HDFRecordReader;
import edu.umn.cs.spatialHadoop.nasa.NASADataset;
import edu.umn.cs.spatialHadoop.nasa.NASAPoint;
import edu.umn.cs.spatialHadoop.nasa.NASARectangle;
import edu.umn.cs.spatialHadoop.nasa.NASAShape;
import edu.umn.cs.spatialHadoop.operations.Aggregate.MinMax;
import edu.umn.cs.spatialHadoop.operations.RangeQuery.RangeFilter;

/**
 * Draws an image of all shapes in an input file.
 * @author Ahmed Eldawy
 *
 */
public class Plot {
  /**Logger*/
  private static final Log LOG = LogFactory.getLog(Plot.class);
  
  private static final String MinValue = "plot.min_value";
  private static final String MaxValue = "plot.max_value";
  /**The grid used to partition data across reducers*/
  private static final String PartitionGrid = "plot.partition_grid";

  /**
   * If the processed block is already partitioned (via global index), then
   * the output is the same as input (Identity map function). If the input
   * partition is not partitioned (a heap file), then the given shape is output
   * to all overlapping partitions.
   * @author Ahmed Eldawy
   *
   */
  public static class PlotMap extends MapReduceBase 
    implements Mapper<Rectangle, Shape, IntWritable, Shape> {
    
    private GridInfo partitionGrid;
    private IntWritable cellNumber;
    private Shape queryRange;
    
    @Override
    public void configure(JobConf job) {
      super.configure(job);
      partitionGrid = (GridInfo) OperationsParams.getShape(job, PartitionGrid);
      cellNumber = new IntWritable();
      queryRange = OperationsParams.getShape(job, "rect");
    }
    
    public void map(Rectangle cell, Shape shape,
        OutputCollector<IntWritable, Shape> output, Reporter reporter)
        throws IOException {
      Rectangle shapeMbr = shape.getMBR();
      if (shapeMbr == null)
        return;
      // Skip shapes outside query range if query range is set
      if (queryRange != null && !shapeMbr.isIntersected(queryRange))
        return;
      java.awt.Rectangle overlappingCells = partitionGrid.getOverlappingCells(shapeMbr);
      for (int i = 0; i < overlappingCells.width; i++) {
        int x = overlappingCells.x + i;
        for (int j = 0; j < overlappingCells.height; j++) {
          int y = overlappingCells.y + j;
          cellNumber.set(y * partitionGrid.columns + x + 1);
          output.collect(cellNumber, shape);
        }
      }
    }
  }
  
  /**
   * The reducer class draws an image for contents (shapes) in each cell info
   * @author Ahmed Eldawy
   *
   */
  public static class PlotReduce extends MapReduceBase
      implements Reducer<IntWritable, Shape, Rectangle, ImageWritable> {
    
    private GridInfo partitionGrid;
    private Rectangle fileMbr;
    private int imageWidth, imageHeight;
    private ImageWritable sharedValue = new ImageWritable();
    private double scale2;
    private Color strokeColor;
    private boolean vflip;

    @Override
    public void configure(JobConf job) {
      System.setProperty("java.awt.headless", "true");
      super.configure(job);
      this.partitionGrid = (GridInfo) OperationsParams.getShape(job, PartitionGrid);
      this.fileMbr = ImageOutputFormat.getFileMBR(job);
      this.imageWidth = job.getInt("width", 1000);
      this.imageHeight = job.getInt("height", 1000);
      this.strokeColor = new Color(job.getInt("color", 0));
      this.vflip = job.getBoolean("vflip", false);
      
      if (vflip) {
        double temp = this.fileMbr.y1;
        this.fileMbr.y1 = -this.fileMbr.y2;
        this.fileMbr.y2 = -temp;
      }

      this.scale2 = (double)imageWidth * imageHeight /
          (this.fileMbr.getWidth() * this.fileMbr.getHeight());

      NASAPoint.minValue = job.getInt(MinValue, 0);
      NASAPoint.maxValue = job.getInt(MaxValue, 65535);
    }

    @Override
    public void reduce(IntWritable cellNumber, Iterator<Shape> values,
        OutputCollector<Rectangle, ImageWritable> output, Reporter reporter)
        throws IOException {
      try {
        CellInfo cellInfo = partitionGrid.getCell(cellNumber.get());
        // Initialize the image
        int image_x1 = (int) Math.floor((cellInfo.x1 - fileMbr.x1) * imageWidth / fileMbr.getWidth());
        int image_y1 = (int) Math.floor(((vflip? -cellInfo.y2 : cellInfo.y1) - fileMbr.y1) * imageHeight / fileMbr.getHeight());
        int image_x2 = (int) Math.ceil((cellInfo.x2 - fileMbr.x1) * imageWidth / fileMbr.getWidth());
        int image_y2 = (int) Math.ceil(((vflip? -cellInfo.y1 : cellInfo.y2) - fileMbr.y1) * imageHeight / fileMbr.getHeight());
        int tile_width = image_x2 - image_x1;
        int tile_height = image_y2 - image_y1;

        BufferedImage image = new BufferedImage(tile_width, tile_height,
            BufferedImage.TYPE_INT_ARGB);
        Color bg_color = new Color(0,0,0,0);

        Graphics2D graphics;
        try {
          graphics = image.createGraphics();
        } catch (Throwable e) {
          graphics = new SimpleGraphics(image);
        }
        graphics.setBackground(bg_color);
        graphics.clearRect(0, 0, tile_width, tile_height);
        graphics.setColor(strokeColor);
        graphics.translate(-image_x1, -image_y1);

        while (values.hasNext()) {
          Shape s = values.next();
          s.draw(graphics, fileMbr, imageWidth, imageHeight, vflip, scale2);
        }
        
        graphics.dispose();
        
        sharedValue.setImage(image);
        output.collect(cellInfo, sharedValue);
      } catch (RuntimeException e) {
        e.printStackTrace();
        throw e;
      }
    }
  }
  
  public static class PlotOutputCommitter extends FileOutputCommitter {
    @Override
    public void commitJob(JobContext context) throws IOException {
      super.commitJob(context);
      
      JobConf job = context.getJobConf();
      Path outFile = ImageOutputFormat.getOutputPath(job);
      int width = job.getInt("width", 1000);
      int height = job.getInt("height", 1000);
      
      // Combine all images in one file
      // Rename output file
      // Combine all output files into one file as we do with grid files
      FileSystem outFs = outFile.getFileSystem(job);
      Path temp = new Path(outFile.toUri().getPath()+"_temp");
      outFs.rename(outFile, temp);
      FileStatus[] resultFiles = outFs.listStatus(temp, new PathFilter() {
        @Override
        public boolean accept(Path path) {
          return path.toUri().getPath().contains("part-");
        }
      });
      
      if (resultFiles.length == 1) {
        // Only one output file
        outFs.rename(resultFiles[0].getPath(), outFile);
      } else {
        // Merge all images into one image (overlay)
        BufferedImage finalImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (FileStatus resultFile : resultFiles) {
          FSDataInputStream imageFile = outFs.open(resultFile.getPath());
          BufferedImage tileImage = ImageIO.read(imageFile);
          imageFile.close();

          Graphics2D graphics;
          try {
            graphics = finalImage.createGraphics();
          } catch (Throwable e) {
            graphics = new SimpleGraphics(finalImage);
          }
          graphics.drawImage(tileImage, 0, 0, null);
          graphics.dispose();
        }
        
        // Finally, write the resulting image to the given output path
        LOG.info("Writing final image");
        OutputStream outputImage = outFs.create(outFile);
        ImageIO.write(finalImage, "png", outputImage);
        outputImage.close();
      }
      
      outFs.delete(temp, true);
    }
  }
  
  
  /**
   * If the processed block is already partitioned (via global index), then
   * the output is the same as input (Identity map function). If the input
   * partition is not partitioned (a heap file), then the given shape is output
   * to all overlapping partitions.
   * @author Ahmed Eldawy
   *
   */
  public static class PlotFastMap extends MapReduceBase 
    implements Mapper<Rectangle, ArrayWritable, Rectangle, ImageWritable> {
  
    /**Only objects inside this query range are drawn*/
    private Shape queryRange;
    private int imageWidth;
    private int imageHeight;
    private Rectangle drawMbr;
    private Color strokeColor;
    private double scale2;
    /**Used to output values*/
    private ImageWritable sharedValue = new ImageWritable();
    
    @Override
    public void configure(JobConf job) {
      System.setProperty("java.awt.headless", "true");
      super.configure(job);
      this.queryRange = OperationsParams.getShape(job, "rect");
      this.drawMbr = queryRange != null ? queryRange.getMBR() : ImageOutputFormat.getFileMBR(job);
      this.imageWidth = job.getInt("width", 1000);
      this.imageHeight = job.getInt("height", 1000);
      this.strokeColor = new Color(job.getInt("color", 0));
      
      this.scale2 = (double)imageWidth * imageHeight /
          (this.drawMbr.getWidth() * this.drawMbr.getHeight());
  
      NASAPoint.minValue = job.getInt(Plot.MinValue, 0);
      NASAPoint.maxValue = job.getInt(Plot.MaxValue, 65535);
    }
  
    @Override
    public void map(Rectangle cell, ArrayWritable value,
        OutputCollector<Rectangle, ImageWritable> output, Reporter reporter)
        throws IOException {
      BufferedImage image = new BufferedImage(imageWidth, imageHeight,
          BufferedImage.TYPE_INT_ARGB);
      
      Color bg_color = new Color(0,0,0,0);
  
      Graphics2D graphics;
      try {
        graphics = image.createGraphics();
      } catch (Throwable e) {
        graphics = new SimpleGraphics(image);
      }
      graphics.setBackground(bg_color);
      graphics.clearRect(0, 0, imageWidth, imageHeight);
      graphics.setColor(strokeColor);
      
      System.out.println("Read values: "+value.get().length);
      for (Shape shape : (Shape[]) value.get()) {
        if (queryRange == null || queryRange.isIntersected(shape))
          shape.draw(graphics, drawMbr, imageWidth, imageHeight, false, scale2);
      }
  
      graphics.dispose();
      
      sharedValue.setImage(image);
      output.collect(drawMbr, sharedValue);
    }
    
  }

  /**
   * The reducer class combines all images into one image by overlaying all of
   * them on top of each other.
   * @author Ahmed Eldawy
   *
   */
  public static class PlotFastReduce extends MapReduceBase
      implements Reducer<Rectangle, ImageWritable, Rectangle, ImageWritable> {
    
    private boolean vflip;
  
    @Override
    public void configure(JobConf job) {
      System.setProperty("java.awt.headless", "true");
      super.configure(job);
    }
  
    @Override
    public void reduce(Rectangle rect, Iterator<ImageWritable> values,
        OutputCollector<Rectangle, ImageWritable> output, Reporter reporter)
        throws IOException {
      if (values.hasNext()) {
        ImageWritable mergedImage = values.next();
        BufferedImage image = mergedImage.getImage();
        Graphics2D graphics;
        try {
          graphics = image.createGraphics();
        } catch (Throwable e) {
          graphics = new SimpleGraphics(image);
        }
        // Overlay all other images on top of it
        while (values.hasNext()) {
          BufferedImage img = values.next().getImage();
          graphics.drawImage(img, 0, 0, null);
        }
        graphics.dispose();
        
        mergedImage.setImage(image);
        output.collect(rect, mergedImage);
      }
    }
  }


  private static RunningJob plotFastMapReduce(Path inFile, Path outFile,
      OperationsParams params) throws IOException {
    boolean background = params.is("background");

    int width = params.getInt("width", 1000);
    int height = params.getInt("height", 1000);

    // Flip image vertically to correct Y axis +ve direction
    boolean vflip = params.is("vflip");

    Color color = params.getColor("color", Color.BLACK);

    String hdfDataset = (String) params.get("dataset");
    Shape shape = hdfDataset != null ? new NASARectangle() : params.getShape("shape", null);
    Shape plotRange = params.getShape("rect", null);

    boolean keepAspectRatio = params.is("keep-ratio", true);

    String valueRangeStr = (String) params.get("valuerange");
    MinMax valueRange;
    if (valueRangeStr == null) {
      valueRange = null;
    } else {
      String[] parts = valueRangeStr.split(",");
      valueRange = new MinMax(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    JobConf job = new JobConf(shape.getClass());
    job.setJobName("Plot");

    job.setMapperClass(PlotMap.class);
    ClusterStatus clusterStatus = new JobClient(job).getClusterStatus();
    job.setNumMapTasks(clusterStatus.getMaxMapTasks() * 5);
    job.setCombinerClass(PlotReduce.class);
    job.setReducerClass(PlotReduce.class);
    //      job.setNumReduceTasks(Math.max(1, clusterStatus.getMaxReduceTasks()));
    job.setNumReduceTasks(0);
    job.setMapOutputKeyClass(Rectangle.class);
    job.setMapOutputValueClass(ImageWritable.class);

    FileSystem inFs = inFile.getFileSystem(job);
    Rectangle fileMBR;
    // Collects some stats about the file to plot it correctly
    if (hdfDataset != null) {
      // Input is HDF
      job.set(HDFRecordReader.DatasetName, hdfDataset);
      job.setBoolean(HDFRecordReader.SkipFillValue, true);
      // Determine the range of values by opening one of the HDF files
      if (valueRange == null)
        valueRange = Aggregate.aggregate(new Path[] {inFile}, params);
      job.setInt(MinValue, valueRange.minValue);
      job.setInt(MaxValue, valueRange.maxValue);
      fileMBR = plotRange != null?
          plotRange.getMBR() : new Rectangle(-180, -140, 180, 169);
          //      job.setClass(HDFRecordReader.ProjectorClass, MercatorProjector.class,
          //          GeoProjector.class);
    } else {
      // Run MBR operation in synchronous mode
      OperationsParams mbrArgs = new OperationsParams(params);
      mbrArgs.setBoolean("background", false);
      fileMBR = plotRange != null ? plotRange.getMBR() :
        FileMBR.fileMBR(inFile, mbrArgs);
    }
    LOG.info("File MBR: "+fileMBR);

    if (keepAspectRatio) {
      // Adjust width and height to maintain aspect ratio
      if (fileMBR.getWidth() / fileMBR.getHeight() > (double) width / height) {
        // Fix width and change height
        height = (int) (fileMBR.getHeight() * width / fileMBR.getWidth());
        // Make divisible by two for compatability with ffmpeg
        height &= 0xfffffffe;
      } else {
        width = (int) (fileMBR.getWidth() * height / fileMBR.getHeight());
      }
    }

    LOG.info("Creating an image of size "+width+"x"+height);
    ImageOutputFormat.setFileMBR(job, fileMBR);
    if (plotRange != null) {
      job.setClass(SpatialSite.FilterClass, RangeFilter.class, BlockFilter.class);
    }

    job.setInputFormat(ShapeArrayInputFormat.class);
    ShapeInputFormat.addInputPath(job, inFile);
    // Set output committer which will stitch images together after all reducers
    // finish
    // job.setOutputCommitter(PlotOutputCommitter.class);

    job.setOutputFormat(ImageOutputFormat.class);
    TextOutputFormat.setOutputPath(job, outFile);

    if (background) {
      JobClient jc = new JobClient(job);
      return lastSubmittedJob = jc.submitJob(job);
    } else {
      return lastSubmittedJob = JobClient.runJob(job);
    }
  }

  /**Last submitted Plot job*/
  public static RunningJob lastSubmittedJob;
  
  private static RunningJob plotMapReduce(Path inFile, Path outFile, OperationsParams params) throws IOException {
      boolean showBorders = params.is("borders");
      boolean background = params.is("background");
      
      int width = params.getInt("width", 1000);
      int height = params.getInt("height", 1000);
      
      // Flip image vertically to correct Y axis +ve direction
      boolean vflip = params.is("vflip");
      
      Color color = params.getColor("color", Color.BLACK);
  
      String hdfDataset = (String) params.get("dataset");
      Shape shape = hdfDataset != null ? new NASARectangle() : params.getShape("shape", null);
      Shape plotRange = params.getShape("rect", null);
  
      boolean keepAspectRatio = params.is("keep-ratio", true);
      
      String valueRangeStr = (String) params.get("valuerange");
      MinMax valueRange;
      if (valueRangeStr == null) {
        valueRange = null;
      } else {
        String[] parts = valueRangeStr.split(",");
        valueRange = new MinMax(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
      }
      
      JobConf job = new JobConf(shape.getClass());
      job.setJobName("Plot");
      
      job.setMapperClass(PlotMap.class);
      ClusterStatus clusterStatus = new JobClient(job).getClusterStatus();
      job.setNumMapTasks(clusterStatus.getMaxMapTasks() * 5);
      job.setReducerClass(PlotReduce.class);
      job.setNumReduceTasks(Math.max(1, clusterStatus.getMaxReduceTasks()));
      job.setMapOutputKeyClass(IntWritable.class);
      job.setMapOutputValueClass(shape.getClass());
  
      Rectangle fileMBR;
      // Collects some stats about the file to plot it correctly
      if (hdfDataset != null) {
        // Input is HDF
        job.set(HDFRecordReader.DatasetName, hdfDataset);
        job.setBoolean(HDFRecordReader.SkipFillValue, true);
        // Determine the range of values by opening one of the HDF files
        if (valueRange == null)
          valueRange = Aggregate.aggregate(new Path[] {inFile}, params);
        job.setInt(MinValue, valueRange.minValue);
        job.setInt(MaxValue, valueRange.maxValue);
        fileMBR = plotRange != null?
            plotRange.getMBR() : new Rectangle(-180, -140, 180, 169);
  //      job.setClass(HDFRecordReader.ProjectorClass, MercatorProjector.class,
  //          GeoProjector.class);
      } else {
        // Run MBR operation in synchronous mode
        OperationsParams mbrArgs = new OperationsParams(params);
        mbrArgs.setBoolean("background", false);
        fileMBR = plotRange != null ? plotRange.getMBR() :
          FileMBR.fileMBR(inFile, mbrArgs);
      }
      LOG.info("File MBR: "+fileMBR);
      
      if (keepAspectRatio) {
        // Adjust width and height to maintain aspect ratio
        if (fileMBR.getWidth() / fileMBR.getHeight() > (double) width / height) {
          // Fix width and change height
          height = (int) (fileMBR.getHeight() * width / fileMBR.getWidth());
          // Make divisible by two for compatability with ffmpeg
          height &= 0xfffffffe;
        } else {
          width = (int) (fileMBR.getWidth() * height / fileMBR.getHeight());
        }
      }
      
      LOG.info("Creating an image of size "+width+"x"+height);
      ImageOutputFormat.setFileMBR(job, fileMBR);
      if (plotRange != null) {
        job.setClass(SpatialSite.FilterClass, RangeFilter.class, BlockFilter.class);
      }
      
      // A heap file. The map function should partition the file
      GridInfo partitionGrid = new GridInfo(fileMBR.x1, fileMBR.y1, fileMBR.x2,
          fileMBR.y2);
      partitionGrid.calculateCellDimensions(
          (int) Math.max(1, clusterStatus.getMaxReduceTasks()));
      SpatialSite.setShape(job, PartitionGrid, partitionGrid);
      
      job.setInputFormat(ShapeInputFormat.class);
      ShapeInputFormat.addInputPath(job, inFile);
      // Set output committer which will stitch images together after all reducers
      // finish
      job.setOutputCommitter(PlotOutputCommitter.class);
      
      job.setOutputFormat(ImageOutputFormat.class);
      TextOutputFormat.setOutputPath(job, outFile);
      
      if (background) {
        JobClient jc = new JobClient(job);
        return lastSubmittedJob = jc.submitJob(job);
      } else {
        return lastSubmittedJob = JobClient.runJob(job);
      }
    }

  private static <S extends Shape> void plotLocal(Path inFile, Path outFile,
      OperationsParams params) throws IOException {
    // TODO: Draw borders
    boolean showBorders = params.is("borders");
    
    int width = params.getInt("width", 1000);
    int height = params.getInt("height", 1000);
    
    // Flip image vertically to correct Y axis +ve direction
    boolean vflip = params.is("vflip");
    
    Color color = params.getColor("color", Color.BLACK);

    String hdfDataset = (String) params.get("dataset");
    Shape shape = hdfDataset != null ? new NASARectangle() : (Shape) params.getShape("shape", null);
    Shape plotRange = params.getShape("rect", null);

    boolean keepAspectRatio = params.is("keep-ratio", true);
    
    String valueRangeStr = (String) params.get("valuerange");
    MinMax valueRange;
    if (valueRangeStr == null) {
      valueRange = null;
    } else {
      String[] parts = valueRangeStr.split(",");
      valueRange = new MinMax(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    Configuration conf = new Configuration();
    FileSystem inFs = inFile.getFileSystem(conf);

    long fileLength = inFs.getFileStatus(inFile).getLen();
    if (hdfDataset != null) {
      // Collects some stats about the HDF file
      if (valueRange == null)
        valueRange = Aggregate.aggregateLocal(new Path[] {inFile}, params);
      Rectangle fileMbr = plotRange == null ? new Rectangle(-180, -90, 180, 90)
          : plotRange.getMBR();
      NASAPoint.minValue = valueRange.minValue;
      NASAPoint.maxValue = valueRange.maxValue;
      LOG.info("FileMBR: "+fileMbr);
      if (vflip) {
        double temp = fileMbr.y1;
        fileMbr.y1 = -fileMbr.y2;
        fileMbr.y2 = -temp;
      }
      
      if (keepAspectRatio) {
        // Adjust width and height to maintain aspect ratio
        if (fileMbr.getWidth() / fileMbr.getHeight() > (double) width / height) {
          // Fix width and change height
          height = (int) (fileMbr.getHeight() * width / fileMbr.getWidth());
        } else {
          width = (int) (fileMbr.getWidth() * height / fileMbr.getHeight());
        }
      }

      // Read points from the HDF file
      RecordReader<NASADataset, NASAShape> reader = new HDFRecordReader(conf,
          new FileSplit(inFile, 0, fileLength, new String[0]), hdfDataset, true);
      NASADataset dataset = reader.createKey();

      // Create an image
      BufferedImage image = new BufferedImage(width, height,
          BufferedImage.TYPE_INT_ARGB);
      Graphics2D graphics = image.createGraphics();
      Color bg_color = new Color(0, 0, 0, 0);
      graphics.setBackground(bg_color);
      graphics.clearRect(0, 0, width, height);
      graphics.setColor(color);
      
      while (reader.next(dataset, (NASAShape)shape)) {
        if (plotRange == null || shape.isIntersected(shape)) {
          shape.draw(graphics, fileMbr, width, height, vflip, 0.0);
        }
      }
      reader.close();

      // Write image to output
      graphics.dispose();
      FileSystem outFs = outFile.getFileSystem(conf);
      OutputStream out = outFs.create(outFile, true);
      ImageIO.write(image, "png", out);
      out.close();
    } else {
      // Determine file MBR to be able to scale shapes correctly
      Rectangle fileMbr = plotRange == null ?
          FileMBR.fileMBR(inFile, params) : plotRange.getMBR();
      LOG.info("FileMBR: "+fileMbr);
      if (vflip) {
        double temp = fileMbr.y1;
        fileMbr.y1 = -fileMbr.y2;
        fileMbr.y2 = -temp;
      }

      if (keepAspectRatio) {
        // Adjust width and height to maintain aspect ratio
        if (fileMbr.getWidth() / fileMbr.getHeight() > (double) width / height) {
          // Fix width and change height
          height = (int) (fileMbr.getHeight() * width / fileMbr.getWidth());
        } else {
          width = (int) (fileMbr.getWidth() * height / fileMbr.getHeight());
        }
      }
      
      double scale2 = (double) width * height
          / (fileMbr.getWidth() * fileMbr.getHeight());

      // Create an image
      BufferedImage image = new BufferedImage(width, height,
          BufferedImage.TYPE_INT_ARGB);
      Graphics2D graphics = image.createGraphics();
      Color bg_color = new Color(0,0,0,0);
      graphics.setBackground(bg_color);
      graphics.clearRect(0, 0, width, height);
      graphics.setColor(color);
      
      ShapeRecordReader<Shape> reader = new ShapeRecordReader<Shape>(conf,
          new FileSplit(inFile, 0, fileLength, new String[0]));
      Rectangle cell = reader.createKey();
      while (reader.next(cell, shape)) {
        Rectangle shapeMBR = shape.getMBR();
        if (shapeMBR != null) {
          if (plotRange == null || shapeMBR.isIntersected(plotRange))
            shape.draw(graphics, fileMbr, width, height, vflip, scale2);
        }
      }
      reader.close();
      // Write image to output
      graphics.dispose();
      FileSystem outFs = outFile.getFileSystem(conf);
      OutputStream out = outFs.create(outFile, true);
      ImageIO.write(image, "png", out);
      out.close();
    }
  }

  public static RunningJob plot(Path inFile, Path outFile, OperationsParams params) throws IOException {
    // Determine the size of input which needs to be processed in order to determine
    // whether to plot the file locally or using MapReduce
    JobConf job = new JobConf(params);
    ShapeInputFormat<Shape> inputFormat = new ShapeInputFormat<Shape>();
    ShapeInputFormat.addInputPath(job, inFile);
    Shape plotRange = params.getShape("rect");
    if (plotRange != null) {
      job.setClass(SpatialSite.FilterClass, RangeFilter.class, BlockFilter.class);
    }
    InputSplit[] splits = inputFormat.getSplits(job, 1);
    boolean autoLocal = splits.length <= 3;
    
    boolean isLocal = params.is("local", autoLocal);
    
    if (isLocal) {
      plotLocal(inFile, outFile, params);
      return null;
    } else if (params.is("fast")){
      return plotFastMapReduce(inFile, outFile, params);
    } else {
      return plotMapReduce(inFile, outFile, params);
    }
  }

  /**
   * Draws an image that can be used as a scale for heat maps generated using
   * Plot or PlotPyramid.
   * @param output - Output path
   * @param valueRange - Range of values of interest
   * @param width - Width of the generated image
   * @param height - Height of the generated image
   * @throws IOException
   */
  public static void drawScale(Path output, MinMax valueRange, int width, int height) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setBackground(Color.BLACK);
    g.clearRect(0, 0, width, height);
    
    for (int y = 0; y < height; y++) {
      float hue = y * NASAPoint.MaxHue / height;
      int color = Color.HSBtoRGB(hue, 0.5f, 1.0f);
      g.setColor(new Color(color));
      g.drawRect(width * 3 / 4, y, width / 4, 1);
    }
    
    int fontSize = 24;
    g.setFont(new Font("Arial", Font.BOLD, fontSize));
    int step = (valueRange.maxValue - valueRange.minValue) * fontSize * 10 / height;
    step = (int) Math.pow(10, Math.round(Math.log10(step)));
    int min_value = valueRange.minValue / step * step;
    int max_value = valueRange.maxValue / step * step;
  
    for (int value = min_value; value <= max_value; value += step) {
      int y = fontSize + (height - fontSize) - value * (height - fontSize) / (valueRange.maxValue - valueRange.minValue);
      g.setColor(Color.WHITE);
      g.drawString(String.valueOf(value), 5, y);
    }
    
    g.dispose();
    
    FileSystem fs = output.getFileSystem(new Configuration());
    FSDataOutputStream outStream = fs.create(output, true);
    ImageIO.write(image, "png", outStream);
    outStream.close();
  }
  
  /**
   * Combines images of different datasets into one image that is displayed
   * to users.
   * This method is called from the web interface to display one image for
   * multiple selected datasets.
   * @param fs The file system that contains the datasets and images
   * @param files Paths to directories which contains the datasets
   * @param includeBoundaries Also plot the indexing boundaries of datasets
   * @return An image that is the combination of all datasets images
   * @throws IOException
   */
  public static BufferedImage combineImages(Configuration conf, Path[] files,
      boolean includeBoundaries, int width, int height) throws IOException {
    BufferedImage result = null;
    // Retrieve the MBRs of all datasets
    Rectangle allMbr = new Rectangle(Double.MAX_VALUE, Double.MAX_VALUE,
        -Double.MAX_VALUE, -Double.MAX_VALUE);
    for (Path file : files) {
      FileSystem fs = file.getFileSystem(conf);
      Rectangle mbr = FileMBR.fileMBR(file, new OperationsParams(conf));
      allMbr.expand(mbr);
    }

    // Adjust width and height to maintain aspect ratio
    if ((allMbr.x2 - allMbr.x1) / (allMbr.y2 - allMbr.y1) > (double) width / height) {
      // Fix width and change height
      height = (int) ((allMbr.y2 - allMbr.y1) * width / (allMbr.x2 - allMbr.x1));
    } else {
      width = (int) ((allMbr.x2 - allMbr.x1) * height / (allMbr.y2 - allMbr.y1));
    }
    result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

      
    for (Path file : files) {
      FileSystem fs = file.getFileSystem(conf);
      if (fs.getFileStatus(file).isDir()) {
        // Retrieve the MBR of this dataset
        Rectangle mbr = FileMBR.fileMBR(file, new OperationsParams(conf));
        // Compute the coordinates of this image in the whole picture
        mbr.x1 = (mbr.x1 - allMbr.x1) * width / allMbr.getWidth();
        mbr.x2 = (mbr.x2 - allMbr.x1) * width / allMbr.getWidth();
        mbr.y1 = (mbr.y1 - allMbr.y1) * height / allMbr.getHeight();
        mbr.y2 = (mbr.y2 - allMbr.y1) * height / allMbr.getHeight();
        // Retrieve the image of this dataset
        Path imagePath = new Path(file, "_data.png");
        if (!fs.exists(imagePath))
          throw new RuntimeException("Image "+imagePath+" not ready");
        FSDataInputStream imageFile = fs.open(imagePath);
        BufferedImage image = ImageIO.read(imageFile);
        imageFile.close();
        // Draw the image
        Graphics graphics = result.getGraphics();
        graphics.drawImage(image, (int) mbr.x1, (int) mbr.y1,
            (int) mbr.getWidth(), (int) mbr.getHeight(), null);
        graphics.dispose();
        
        if (includeBoundaries) {
          // Plot also the image of the boundaries
          // Retrieve the image of the dataset boundaries
          imagePath = new Path(file, "_partitions.png");
          if (fs.exists(imagePath)) {
            imageFile = fs.open(imagePath);
            image = ImageIO.read(imageFile);
            imageFile.close();
            // Draw the image
            graphics = result.getGraphics();
            graphics.drawImage(image, (int) mbr.x1, (int) mbr.y1,
                (int) mbr.getWidth(), (int) mbr.getHeight(), null);
            graphics.dispose();
          }
        }
      }
    }
    
    return result;
  }
  
  
  private static void printUsage() {
    System.out.println("Plots all shapes to an image");
    System.out.println("Parameters: (* marks required parameters)");
    System.out.println("<input file> - (*) Path to input file");
    System.out.println("<output file> - (*) Path to output file");
    System.out.println("shape:<point|rectangle|polygon|ogc> - (*) Type of shapes stored in input file");
    System.out.println("width:<w> - Maximum width of the image (1000)");
    System.out.println("height:<h> - Maximum height of the image (1000)");
    System.out.println("color:<c> - Main color used to draw the picture (black)");
    System.out.println("-borders: For globally indexed files, draws the borders of partitions");
    System.out.println("-showblockcount: For globally indexed files, draws the borders of partitions");
    System.out.println("-showrecordcount: Show number of records in each partition");
    System.out.println("-overwrite: Override output file without notice");
    System.out.println("-vflip: Vertically flip generated image to correct +ve Y-axis direction");
    
    GenericOptionsParser.printGenericCommandUsage(System.out);
  }
  
  /**
   * @param args
   * @throws IOException 
   */
  public static void main(String[] args) throws IOException {
    System.setProperty("java.awt.headless", "true");
    OperationsParams params = new OperationsParams(new GenericOptionsParser(args));
    if (!params.checkInputOutput()) {
      printUsage();
      System.exit(1);
    }
    
    Path inFile = params.getInputPath();
    Path outFile = params.getOutputPath();

    plot(inFile, outFile, params);
  }

}
