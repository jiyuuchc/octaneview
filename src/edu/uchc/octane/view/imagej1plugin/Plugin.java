package edu.uchc.octane.view.imagej1plugin;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.Roi;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ij.process.ShortProcessor;
import java.awt.AWTEvent;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.prefs.Preferences;

import org.apache.commons.math3.util.FastMath;

import edu.uchc.octane.core.datasource.OctaneDataFile;
import edu.uchc.octane.core.localizationimage.RasterizedLocalizationImage;

public class Plugin implements ij.plugin.PlugIn {

    private static final String PIXEL_SIZE = "pixel_size";
    private static final String LOCAL_DENSITY_RADIUS = "local_density_radius";
    
    Preferences prefs = Preferences.userNodeForPackage(getClass());
    
    public Plugin() {
    }

    class RenderingCallback implements Runnable {
        final ImagePlus imp;
        final RasterizedLocalizationImage data;
        volatile boolean running;

        public RenderingCallback(ImagePlus imp) {
            this.imp = imp;
            data = ((RasterizedLocalizationImage) imp.getProperty("LocalizationData"));
            running = true;
        }

        public void run() {
            imp.updateAndDraw();
        }
    }

    public void run(String cmd) {
        RasterizedLocalizationImage d;
        
        if (cmd.equals("Prefs")) {
            setPreferences();
            return;
        }
        
        if (cmd.equals("Load")) {
            OpenDialog dlg = new OpenDialog(cmd);
            
            if (dlg.getPath() != null) {
                
                try {
                    ObjectInputStream fi = new ObjectInputStream(new java.io.FileInputStream(dlg.getPath()));
                    d = new RasterizedLocalizationImage((OctaneDataFile) fi.readObject(), prefs.getDouble(PIXEL_SIZE, 16.0));
                    fi.close();
                } catch (IOException e) {
                    IJ.log("IO error");
                    IJ.log(e.getMessage());
                    return;
                } catch (ClassNotFoundException e) {
                    IJ.log("Doesn't seem to be the right file type: class not found");
                    IJ.log(e.getMessage());
                    return;
                }

                // pixel array is shared between the ImagePlus instance and the
                // LocalizationImage instance.
                ShortProcessor ip = new ShortProcessor(d.getDimX(), d.getDimY(), d.getRendered(), null);
                ImagePlus imp = new ImagePlus(dlg.getFileName(), ip);
                imp.setProperty("LocalizationData", d);
                imp.show();
            }

            return;
        }

        ImagePlus imp = IJ.getImage();
        d = (RasterizedLocalizationImage) imp.getProperty("LocalizationData");
        if (d == null) {
            IJ.error("Not a localization image");
            return;
        }

        if (cmd.equals("ZoomIn")) {
            Roi roi = imp.getRoi();
            if (roi == null) {
                return;
            }
            d.setRoi(roi.getBounds());
            ShortProcessor ip = new ShortProcessor(d.getRoi().width, d.getRoi().height, d.getRendered(), null);
            imp.setProcessor(ip);
        }

        if (cmd.equals("ZoomOut")) {
            d.setRoi(null);
            ShortProcessor ip = new ShortProcessor(d.getDimX(), d.getDimY(), d.getRendered(), null);
            imp.setProcessor(ip);
        }

        if (cmd.equals("ViewSettings")) {
            changeViewSettings(d, imp);
        }

        if (cmd.equals("KDTree")) {
            buildKDTree(d, imp);
        }

        if (cmd.equals("Save")) {
            try {
                saveFiltered(d, imp);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        if (cmd.equals("Append")) {
            appendNewData(d, imp);
        }
    }

    void buildKDTree(RasterizedLocalizationImage data, ImagePlus imp) {
        Thread progressThread = new Thread() {
            double progress = 0.0D;

            public void run() {
                while (!isInterrupted()) {
                    IJ.showProgress(progress);
                    progress += 0.01D;
                    if (progress >= 1.0D)
                        progress = 0.0D;
                    try {
                        sleep(100L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                IJ.showProgress(1.0D);
            }
        };
        progressThread.start();
        data.measureLocalDensity(prefs.getDouble(LOCAL_DENSITY_RADIUS, 250.0));
        //data.measureLocalDensity(50);
        progressThread.interrupt();
    }

    void changeViewSettings(final RasterizedLocalizationImage data, ImagePlus imp) {
        int frameCol, intensityCol, sigmaCol;
        frameCol = data.frameCol;
        intensityCol = data.intensityCol;
        sigmaCol = data.sigmaCol;

        int densityCol = data.getColFromHeader("density");
        
        HashMap<Integer, double[]> oldFilters = new HashMap<Integer, double[]>();

        GenericDialog dlg = new NonBlockingGenericDialog("Change View Setting: " + imp.getTitle());
        if (frameCol != -1) {
            double minFrame = data.getSummaryStatistics(frameCol).getMin();
            double maxFrame = data.getSummaryStatistics(frameCol).getMax();
            double[] f = data.getViewFilter(frameCol);
            dlg.addSlider("Frame: min", minFrame, maxFrame, f != null ? f[0] : minFrame);
            dlg.addSlider("Frame: max", minFrame, maxFrame, f != null ? f[1] : maxFrame);
            oldFilters.put(frameCol, f);
        }
        if (intensityCol != -1) {
            double[] f = data.getViewFilter(intensityCol);
            dlg.addSlider("Intensity^.5: min", 0.0D, 1000.0, f != null ? FastMath.sqrt(f[0]) : 0.0);
            dlg.addSlider("Intensity^.5: max", 0.0D, 1000.0, f != null ? FastMath.sqrt(f[1]) : 1000.0);
            oldFilters.put(intensityCol, f);
        }
        if (sigmaCol != -1) {
            double[] f = data.getViewFilter(sigmaCol);
            dlg.addSlider("Sigma: min", 0.0D, 500.0D, f != null ? f[0] : 0.0D);
            dlg.addSlider("Sigma: max", 0.0D, 500.0D, f != null ? f[1] : 500.0D);
            oldFilters.put(sigmaCol, f);
        }
        if (densityCol != -1) {
            double maxDensity = data.getSummaryStatistics(densityCol).getMax();
            double[] f = data.getViewFilter(densityCol);
            dlg.addSlider("Density: min", 0, maxDensity, f != null ? f[0] : 0);
            oldFilters.put(densityCol, f);
        }

        dlg.addDialogListener(new DialogListener() {

            @Override
            public boolean dialogItemChanged(GenericDialog dlg, AWTEvent e) {

                if (frameCol != -1) {
                    double minFrame = dlg.getNextNumber();
                    double maxFrame = dlg.getNextNumber();
                    if (minFrame < maxFrame) {
                        data.addViewFilter(frameCol, new double[] { minFrame, maxFrame });
                    }
                }

                if (intensityCol != -1) {
                    double minInt = dlg.getNextNumber();
                    double maxInt = dlg.getNextNumber();
                    if (minInt < maxInt) {
                        data.addViewFilter(intensityCol, new double[] { minInt * minInt, maxInt * maxInt });
                    }
                }

                if (sigmaCol != -1) {
                    double minsigma = dlg.getNextNumber();
                    double maxSigma = dlg.getNextNumber();
                    if (minsigma < maxSigma) {
                        data.addViewFilter(sigmaCol, new double[] { minsigma, maxSigma });
                    }
                }
                
                if (densityCol != -1) {
    				double th = dlg.getNextNumber();
    				data.addViewFilter(densityCol, new double [] {th, Double.MAX_VALUE});                	
                }

                data.startRendering();
                return true;
            }
        });
        data.onRenderingDone(new RenderingCallback(imp));
        dlg.showDialog();
        if (dlg.wasCanceled()) {
            // restore old filter values
            for (Integer key:oldFilters.keySet()) {
                data.addViewFilter(key, oldFilters.get(key));
            }
            data.getRendered();
        }
        data.onRenderingDone(null);
    }

    void saveFiltered(final RasterizedLocalizationImage data, ImagePlus imp) throws IOException {
        double[][] newData = data.getFilteredData();
        OctaneDataFile odf = new OctaneDataFile(newData, data.getDataSource().headers);

        SaveDialog dlg = new SaveDialog("Save", imp.getTitle(), null);
        if (dlg.getFileName() != null) {
            String outPath = dlg.getDirectory() + dlg.getFileName();
            ObjectOutputStream fo = new ObjectOutputStream(new FileOutputStream(outPath));
            fo.writeObject(odf);
            fo.close();
        }
    }
    
    void appendNewData(final RasterizedLocalizationImage data, ImagePlus imp) {
        OpenDialog dlg = new OpenDialog("Append");
        OctaneDataFile odf = null;

        if (dlg.getPath() != null) {
            try {
                ObjectInputStream fi = new ObjectInputStream(new java.io.FileInputStream(dlg.getPath()));
                odf = (OctaneDataFile) fi.readObject();
                fi.close();
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
        }
        
        data.mergeWith(odf);
        ShortProcessor ip = new ShortProcessor(data.getRoi().width, data.getRoi().height, data.getRendered(), null);
        imp.setProcessor(ip);
    }
    
    void setPreferences() {
        GenericDialog dlg = new GenericDialog("OctaneView: Prefs");
        dlg.addNumericField("Pixel size", prefs.getDouble(PIXEL_SIZE, 16.0), 1);
        dlg.addNumericField("Local-density radius", prefs.getDouble(LOCAL_DENSITY_RADIUS, 250.0), 1);
        
        dlg.showDialog();
        
        if (dlg.wasOKed()) {
            double p = dlg.getNextNumber();
            if (p > 0) {
                prefs.putDouble(PIXEL_SIZE, p);
            }
            double ldr = dlg.getNextNumber();
            if (ldr > 0) {
            	prefs.putDouble(LOCAL_DENSITY_RADIUS, ldr);
            }
        }
    }

    public static void main(String... args) throws Exception {
        ImageJ ij = new ImageJ();
        Plugin plugin = new Plugin();
        plugin.run("Load");
        plugin.run("KDTree");
        plugin.run("ViewSettings");
        //IJ.getImage().setRoi(new java.awt.Rectangle(1560, 1560, 2000, 2000));
        //plugin.run("ZoomIn");
        //plugin.run("ViewSettings");
        // plugin.run("ZoomOut");
        //plugin.run("Save");
    }
}
