package edu.uchc.octane.view.imagej1plugin;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.HistogramWindow;
import ij.gui.NonBlockingGenericDialog;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import java.awt.AWTEvent;
import java.awt.Rectangle;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.prefs.Preferences;

import org.apache.commons.math3.util.FastMath;

import edu.uchc.octane.core.localizationdata.LocalizationDataset;
import edu.uchc.octane.core.localizationimage.RasterizedLocalizationImage;

public class Plugin implements ij.plugin.PlugIn {

    private static final String PIXEL_SIZE = "pixel_size";
    private static final String LOCAL_DENSITY_RADIUS = "local_density_radius";
    private static final String Z_SLICE_DEPTH = "z_slice_depth";
    
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
                    d = new RasterizedLocalizationImage((LocalizationDataset) fi.readObject(), prefs.getDouble(PIXEL_SIZE, 16.0));
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
            Rectangle roi = imp.getRoi().getBounds();
            if (roi == null) {
                return;
            }
            Rectangle oldroi = d.getRoi();
            roi.x += oldroi.x - 1;
            roi.y += oldroi.y - 1;
            d.setRoi(roi);
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
        
        if (cmd.equals("zSlice")) {
        	selectSlice(d, imp);
        }
        
        if (cmd.equals("zSliceAll")) {
        	d.setViewFilter(d.zCol, null);
        	d.getRendered();
        	imp.updateAndDraw();
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
        
        //show histogram
        double [] densities = data.getData("density");
        if (densities != null) {

        	FloatProcessor ip = new FloatProcessor(1, densities.length, densities);
        	ImagePlus tmpImp = new ImagePlus("", ip);
        	HistogramWindow hw = new HistogramWindow("Local Density Histogram", tmpImp, 20);
        	hw.setVisible(true);
        	tmpImp.close();
        	
            double [] logDensities = new double[densities.length];
            for (int i = 0; i < densities.length; i++) {
            	logDensities[i] = FastMath.log(densities[i]);
            }        	
        	ip = new FloatProcessor(1, logDensities.length, logDensities);
        	tmpImp = new ImagePlus("", ip);
        	hw = new HistogramWindow("Local Density Histogram - Log Scale", tmpImp, 20);
        	hw.setVisible(true);
        	tmpImp.close();
        }
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
                    if (minFrame <= maxFrame) {
                        data.setViewFilter(frameCol, new double[] { minFrame, maxFrame });
                    }
                }

                if (intensityCol != -1) {
                    double minInt = dlg.getNextNumber();
                    double maxInt = dlg.getNextNumber();
                    if (minInt < maxInt) {
                        data.setViewFilter(intensityCol, new double[] { minInt * minInt, maxInt * maxInt });
                    }
                }

                if (sigmaCol != -1) {
                    double minsigma = dlg.getNextNumber();
                    double maxSigma = dlg.getNextNumber();
                    if (minsigma < maxSigma) {
                        data.setViewFilter(sigmaCol, new double[] { minsigma, maxSigma });
                    }
                }
                
                if (densityCol != -1) {
    				double th = dlg.getNextNumber();
    				data.setViewFilter(densityCol, new double [] {th, Double.MAX_VALUE});                	
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
                data.setViewFilter(key, oldFilters.get(key));
            }
            data.getRendered();
        }
        data.onRenderingDone(null);
    }

    void saveFiltered(final RasterizedLocalizationImage data, ImagePlus imp) throws IOException {
        double[][] newData = data.getFilteredData();

        //ignore zSlice selection
        double [] zFilter = data.getViewFilter(data.zCol);
        data.setViewFilter(data.zCol, null);
        
        LocalizationDataset odf = new LocalizationDataset(newData, data.getDataSource().headers);
        SaveDialog dlg = new SaveDialog("Save", imp.getTitle(), null);
        if (dlg.getFileName() != null) {
            String outPath = dlg.getDirectory() + dlg.getFileName();
            ObjectOutputStream fo = new ObjectOutputStream(new FileOutputStream(outPath));
            fo.writeObject(odf);
            fo.close();
        }

        data.setViewFilter(data.zCol, zFilter);
    }

    void appendNewData(final RasterizedLocalizationImage data, ImagePlus imp) {
        OpenDialog dlg = new OpenDialog("Append");
        LocalizationDataset odf = null;

        if (dlg.getPath() != null) {
            try {
                ObjectInputStream fi = new ObjectInputStream(new java.io.FileInputStream(dlg.getPath()));
                odf = (LocalizationDataset) fi.readObject();
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
        dlg.addNumericField("Local-density radius", prefs.getDouble(LOCAL_DENSITY_RADIUS, 250.0), 0);
        dlg.addNumericField("Z slice depth", prefs.getDouble(Z_SLICE_DEPTH, 100), 0);
        
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
            double zDepth = dlg.getNextNumber();
            if (zDepth > 0) {
            	prefs.putDouble(Z_SLICE_DEPTH, zDepth);
            }
        }
    }

    void selectSlice(final RasterizedLocalizationImage data, ImagePlus imp) {
    
    	if (data.is2DData()) {
    		IJ.error("Not 3d Data");
    		return;
    	}

    	GenericDialog dlg = new NonBlockingGenericDialog("Select Z Slice: " + imp.getTitle());
        double minZ = data.getSummaryStatistics(data.zCol).getMin();
        double maxZ = data.getSummaryStatistics(data.zCol).getMax();
        double[] f = data.getViewFilter(data.zCol);
        double zDepth = prefs.getDouble(Z_SLICE_DEPTH, 200);
        int nSlices = (int)((maxZ - minZ) / zDepth) + 1; 
        dlg.addSlider("Select Z Slice", 1, nSlices, 1);
        
        dlg.addDialogListener(new DialogListener() {

			@Override
			public boolean dialogItemChanged(GenericDialog dlg, AWTEvent e) {
				double s = dlg.getNextNumber();
				data.setViewFilter(data.zCol, new double[] {minZ + (s - 1) * zDepth, minZ + s * zDepth});
				data.startRendering();
				return true;
			}
        	
        });
        
        data.onRenderingDone(new RenderingCallback(imp));
        dlg.showDialog();
        if (dlg.wasCanceled()) {
        	data.setViewFilter(data.zCol, f);
            data.getRendered();
        }
        data.onRenderingDone(null);
    }
    
    public static void main(String... args) throws Exception {
        ImageJ ij = new ImageJ();
        Plugin plugin = new Plugin();
        plugin.run("Load");
        // IJ.getImage().setRoi(new java.awt.Rectangle(1560, 1560, 500, 500));
        // plugin.run("ZoomIn");
        // plugin.run("KDTree");
        //plugin.run("ViewSettings");        
        //plugin.run("ViewSettings");
        // plugin.run("ZoomOut");
        //plugin.run("Save");
    }
}
