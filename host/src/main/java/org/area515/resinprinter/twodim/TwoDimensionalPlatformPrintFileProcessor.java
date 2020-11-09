package org.area515.resinprinter.twodim;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.Future;

import javax.xml.bind.annotation.XmlTransient;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.area515.resinprinter.display.InappropriateDeviceException;
import org.area515.resinprinter.exception.SliceHandlingException;
import org.area515.resinprinter.job.AbstractPrintFileProcessor;
import org.area515.resinprinter.job.JobManagerException;
import org.area515.resinprinter.job.JobStatus;
import org.area515.resinprinter.job.Previewable;
import org.area515.resinprinter.job.PrintJob;
import org.area515.resinprinter.job.render.CurrentImageRenderer;
import org.area515.resinprinter.job.render.RenderedData;
import org.area515.resinprinter.job.render.RenderingCache;
import org.area515.resinprinter.printer.SlicingProfile.TwoDimensionalSettings;
import org.area515.resinprinter.server.Main;

public abstract class TwoDimensionalPlatformPrintFileProcessor<T,E> extends AbstractPrintFileProcessor<T,E> implements Previewable {
    private static final Logger logger = LogManager.getLogger();
    
	@Override
	public void prepareEnvironment(final File processingFile, final PrintJob printJob) throws JobManagerException {
		DataAid aid;
		try {
			aid = initializeJobCacheWithDataAid(printJob);
			createRenderer(aid, this, Boolean.TRUE);
		} catch (InappropriateDeviceException e) {
			throw new JobManagerException("Couldn't create job", e);
		}
	}
	
	protected CurrentImageRenderer buildPlatformRenderer(DataAid dataAid, Object nextRenderingPointer, int totalPlatformSlices, TwoDimensionalImageRenderer platformSizeInitializer) {
		return new PlatformImageRenderer(dataAid, this, nextRenderingPointer, totalPlatformSlices, platformSizeInitializer);
	}
	
	@Override
	public JobStatus processFile(PrintJob printJob) throws Exception {
		DataAid dataAid = getDataAid(printJob);
		try {
			performHeader(dataAid);
			
			int startingSlice = dataAid.customizer.getNextSlice();
			int platformSlices = getSuggestedPlatformLayerCount(dataAid);
			int totalPlatformSlices = platformSlices;
			int extrusionSlices = getSuggested2DExtrusionLayerCount(dataAid);
			
			if (startingSlice >= platformSlices) {
				platformSlices = 0;
				startingSlice -= platformSlices;
			}
			extrusionSlices -= startingSlice;
			
			printJob.setTotalSlices(platformSlices + extrusionSlices);
			RenderingCache printState = dataAid.cache;
			Boolean nextRenderingPointer = (Boolean)printState.getCurrentRenderingPointer();
			Future<RenderedData> currentImage = null;
			TwoDimensionalImageRenderer platformSizeInitializer = null;
			CurrentImageRenderer currentRendering = null;
			if (platformSlices > 0) {
				platformSizeInitializer = createRenderer(dataAid, this, nextRenderingPointer);
				currentRendering = buildPlatformRenderer(dataAid, nextRenderingPointer, totalPlatformSlices, platformSizeInitializer);
				currentImage = Main.GLOBAL_EXECUTOR.submit(currentRendering);
			} else {
				currentRendering = createRenderer(dataAid, this, nextRenderingPointer);
				currentImage = Main.GLOBAL_EXECUTOR.submit(currentRendering);
			}
			while (platformSlices > 0 || extrusionSlices > 0) {
				
				//Performs all of the duties that are common to most print files
				JobStatus status = performPreSlice(dataAid, currentRendering.getScriptEngine(), null);
				if (status != null) {
					return status;
				}
				
				//Wait until the image has been properly rendered. Most likely, it's already done though...
				RenderedData rendered = currentImage.get();
				
				//Now that the image has been rendered, we can make the switch to use the pointer that we were using while we were rendering
				printState.setCurrentRenderingPointer(nextRenderingPointer);
				
				//Get the next pointer in line to start rendering the image into
				nextRenderingPointer = !nextRenderingPointer;
				
				//Start to render the next image while we are waiting for the current image to cure
				if (platformSlices > 1) {
					currentRendering = buildPlatformRenderer(dataAid, nextRenderingPointer, totalPlatformSlices, platformSizeInitializer);
					currentImage = Main.GLOBAL_EXECUTOR.submit(currentRendering);
					platformSlices--;
				} else if (extrusionSlices > 1) {
					//Clear cache so that the text will render for the first time and not use the build platform cache
					if (platformSlices == 1) {
						platformSlices--;
						dataAid.cache.clearCache(Boolean.TRUE);
						dataAid.cache.clearCache(Boolean.FALSE);
					} else {
						extrusionSlices--;
					}
					currentRendering = createRenderer(dataAid, this, nextRenderingPointer);
					currentImage = Main.GLOBAL_EXECUTOR.submit(currentRendering);
				} else {
					extrusionSlices--;
				}

				//Performs all of the duties that are common to most print files
				status = printImageAndPerformPostProcessing(dataAid, rendered.getScriptEngine(), rendered.getPrintableImage());
				if (status != null) {
					return status;
				}
			}
			
			return performFooter(dataAid);
		} finally {
			clearDataAid(dataAid.printJob);
		}
	}
	
	@XmlTransient
	public int getSuggestedPlatformLayerCount(DataAid aid) {
		int fallbackAmount = aid.inkConfiguration.getNumberOfFirstLayers() * 2;
		TwoDimensionalSettings settings = aid.slicingProfile.getTwoDimensionalSettings();
		if (settings == null) {
			return fallbackAmount;
		}
		Double platformHeight = settings.getPlatformHeightMM();
		if (platformHeight == null) {
			return fallbackAmount;
		}
		
		return (int)Math.round(platformHeight / aid.inkConfiguration.getSliceHeight());
	}
	
	@XmlTransient
	public int getSuggested2DExtrusionLayerCount(DataAid aid) {
		int fallbackAmount = aid.inkConfiguration.getNumberOfFirstLayers() * 3;
		TwoDimensionalSettings settings = aid.slicingProfile.getTwoDimensionalSettings();
		if (settings == null) {
			return fallbackAmount;
		}
		Double extrusionHeight = settings.getExtrusionHeightMM();
		if (extrusionHeight == null) {
			return fallbackAmount;
		}
		
		return (int)Math.round(extrusionHeight / aid.inkConfiguration.getSliceHeight());
	}
	
	@Override
	public BufferedImage renderPreviewImage(final DataAid dataAid) throws SliceHandlingException {
		try {
			int platformSlices = getSuggestedPlatformLayerCount(dataAid);
			dataAid.printJob.setTotalSlices(platformSlices + getSuggested2DExtrusionLayerCount(dataAid));
			
			TwoDimensionalImageRenderer extrusionRenderer = createRenderer(dataAid, this, Boolean.TRUE);
			CurrentImageRenderer platformRenderer = buildPlatformRenderer(dataAid, Boolean.FALSE, platformSlices, extrusionRenderer);
			
			//We do this because there could be actions in here that are executed that the extrusionRenderer depends on being executed first
			platformRenderer.call().getPrintableImage();
			CurrentImageRenderer targetRenderer = dataAid.customizer.getNextSlice() < platformSlices?
					platformRenderer:
					extrusionRenderer;
			return targetRenderer.call().getPrintableImage();
		} catch (JobManagerException e) {
			throw new SliceHandlingException(e);
		}
	}
	
	public abstract TwoDimensionalImageRenderer createRenderer(DataAid aid, AbstractPrintFileProcessor<?,?> processor, Object imageIndexToBuild);
}
