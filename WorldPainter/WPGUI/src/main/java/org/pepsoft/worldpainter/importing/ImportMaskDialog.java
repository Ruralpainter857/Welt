/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

/*
 * ImportHeightMapDialog.java
 *
 * Created on 22-jan-2012, 19:47:55
 */
package org.pepsoft.worldpainter.importing;

import org.pepsoft.util.IconUtils;
import org.pepsoft.util.ProgressReceiver;
import org.pepsoft.util.swing.ProgressDialog;
import org.pepsoft.util.swing.ProgressTask;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.heightMaps.CachedImageHeightMap;
import org.pepsoft.worldpainter.layers.Void;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.themes.TerrainListCellRenderer;
import org.pepsoft.worldpainter.util.ImageUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

import static java.util.Arrays.asList;
import static java.util.Comparator.comparing;
import static org.pepsoft.util.CollectionUtils.nullAnd;
import static org.pepsoft.worldpainter.importing.MaskImporter.InputType.*;
import static org.pepsoft.worldpainter.util.BiomeUtils.getBiomeScheme;


/**
 *
 * @author pepijn
 */
@SuppressWarnings({"unused", "FieldCanBeLocal"}) // Managed by NetBeans
public class ImportMaskDialog extends WorldPainterDialog implements DocumentListener {
    public ImportMaskDialog(Window parent, Dimension dimension, ColourScheme colourScheme, List<Layer> allLayers, CustomBiomeManager customBiomeManager, File preselectedFile) {
        super(parent);
        this.dimension = dimension;
        this.colourScheme = colourScheme;
        this.allLayers = allLayers;
        this.customBiomeManager = customBiomeManager;

        initComponents();

        fieldFilename.getDocument().addDocumentListener(this);
        allLayers.sort(comparing(layer -> layer instanceof CustomLayer)
                .thenComparing(layer -> ((Layer) layer).getName()));
        comboBoxLayer.setModel(new DefaultComboBoxModel<>(allLayers.toArray(new Layer[allLayers.size()])));
        comboBoxLayer.setRenderer(new LayerListCellRenderer());
        comboBoxApplyToTerrain.setModel(new DefaultComboBoxModel<>(nullAnd(asList(Terrain.getConfiguredValues())).toArray(new Terrain[0])));
        comboBoxApplyToTerrain.setRenderer(new TerrainListCellRenderer(colourScheme, "-all-"));
        fieldFilename.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { loadFile(); }
            @Override public void removeUpdate(DocumentEvent e) { loadFile(); }
            @Override public void changedUpdate(DocumentEvent e) { loadFile(); }
        });
        comboBoxMapping.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value != null) {
                    setText(((Mapping) value).getDescription());
                }
                return this;
            }
        });
        labelMaskRange.setVisible(false);

        rootPane.setDefaultButton(buttonOk);

        scaleToUI();
        pack();
        setLocationRelativeTo(parent);

        setControlStates();

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                if ((preselectedFile != null) && preselectedFile.isFile()) {
                    fieldFilename.setText(preselectedFile.getAbsolutePath());
                } else {
                    selectFile();
                }
            }
        });
    }

    // DocumentListener
    
    @Override
    public void insertUpdate(DocumentEvent e) {
        setControlStates();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        setControlStates();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        setControlStates();
    }

    private void loadFile() {
        File file = new File(fieldFilename.getText());
        if (file.isFile() && (! file.equals(selectedFile))) {
            selectedFile = file;
            loadImage();
        } else {
            selectedFile = null;
            if (loader.isLoading()) {
                // The field no longer shows the file being loaded
                loader.cancel();
                labelImageDimensions.setText(null);
            }
        }
        setControlStates();
    }

    private void updatePossibleMappings() {
        boolean fileSelected = isMaskReady();
        boolean targetSelected = (radioButtonLayer.isSelected() && (comboBoxLayer.getSelectedItem() != null)) || radioButtonTerrain.isSelected();
        if (fileSelected && targetSelected) {
            final MaskImporter.PossibleMappingsResult possibleMappings = maskImporter.getPossibleMappings();
            if (! possibleMappings.mappings.isEmpty()) {
                comboBoxMapping.setModel(new DefaultComboBoxModel<>(possibleMappings.mappings.toArray(new Mapping[possibleMappings.mappings.size()])));
                pack();
            } else {
                comboBoxMapping.setModel(new DefaultComboBoxModel<>());
            }
            labelReason.setText((possibleMappings.reason != null) ? (possibleMappings.reason + " ") : null);
        }
    }

    /** Whether the selected file is loaded and ready to import: not while it is still loading. */
    private boolean isMaskReady() {
        return (selectedFile != null) && selectedFile.isFile() && (maskImporter != null);
    }

    private void setControlStates() {
        boolean fileSelected = isMaskReady();
        radioButtonTerrain.setEnabled(fileSelected);
        comboBoxApplyToTerrain.setEnabled(fileSelected && radioButtonTerrain.isSelected());
        radioButtonLayer.setEnabled(fileSelected);
        comboBoxLayer.setEnabled(fileSelected && radioButtonLayer.isSelected());
        comboBoxApplyToLayerValue.setEnabled(fileSelected && radioButtonLayer.isSelected() && (comboBoxLayer.getSelectedItem() != null) && (comboBoxApplyToLayerValue.getItemCount() > 1));
        checkBoxRemoveExisting.setEnabled(fileSelected && radioButtonLayer.isSelected());
        boolean targetSelected = (radioButtonLayer.isSelected() && (comboBoxLayer.getSelectedItem() != null)) || radioButtonTerrain.isSelected();
        comboBoxMapping.setEnabled(fileSelected && targetSelected);
        spinnerThreshold.setEnabled(fileSelected && targetSelected && (comboBoxMapping.getSelectedItem() != null) && ((Mapping) comboBoxMapping.getSelectedItem()).isThreshold());
        boolean mappingSelected = comboBoxMapping.getSelectedItem() != null;
        buttonOk.setEnabled(fileSelected && targetSelected && mappingSelected);
    }

    private void loadLayerValues() {
        final Platform platform = dimension.getWorld().getPlatform();
        final Layer layer = (Layer) comboBoxLayer.getSelectedItem();
        final Vector<Integer> values;
        Integer defaultValue = (Integer) comboBoxApplyToLayerValue.getSelectedItem();
        if (layer instanceof Biome) {
            values = new Vector<>(256);
            values.add(null);
            final BiomeScheme biomeScheme = getBiomeScheme(platform);
            for (int value = 0; value < 256; value++) {
                if (biomeScheme.isBiomePresent(value)) {
                    values.add(value);
                } else {
                    final int finalValue = value;
                    if (customBiomeManager.getCustomBiomes().stream().anyMatch(customBiome -> customBiome.getId() == finalValue)) {
                        values.add(value);
                    }
                }
            }
        } else {
            final Layer.DataSize dataSize = layer.getDataSize();
            switch (dataSize) {
                case BIT:
                case BIT_PER_CHUNK:
                    defaultValue = (maskImporter.getInputType() == ONE_BIT_GREY_SCALE) ? 1 : null;
                    values = new Vector<>(1);
                    values.add(defaultValue);
                    break;
                case NIBBLE:
                    values = new Vector<>(17);
                    values.add(null);
                    for (int value = 0; value < 16; value++) {
                        if (value == layer.getDefaultValue()) {
                            continue;
                        }
                        values.add(value);
                    }
                    if ((defaultValue == null) && (maskImporter.getInputType() == ONE_BIT_GREY_SCALE)) {
                        defaultValue = 8;
                    }
                    break;
                default:
                    throw new UnsupportedOperationException("Layer " + layer + " of type " + layer.getClass().getSimpleName() + " with data size " + dataSize + " not supported");
            }
        }
        comboBoxApplyToLayerValue.setModel(new DefaultComboBoxModel<>(values));
        comboBoxApplyToLayerValue.setRenderer(new LayerValueListCellRenderer(layer, platform, colourScheme, customBiomeManager, "-full range-"));
        comboBoxApplyToLayerValue.setSelectedItem(defaultValue);
        pack();
    }

    /** Load the selected file in the background, after releasing the current mask to make its memory available. */
    private void loadImage() {
        image = null;
        releaseCapture();
        maskImporter = null;
        labelImageDimensions.setForeground(null);
        labelImageDimensions.setIcon(null);
        labelImageDimensions.setText("Loading image...");
        labelMaskRange.setVisible(false);
        final File file = selectedFile;
        loader.load(cancellation -> load(dimension, file, cancellation), this::maskLoaded, this::maskLoadFailed);
    }

    /**
     * Load a mask off the event dispatch thread: captured with bounded memory if enabled and supported, otherwise
     * completely, and analysed. Expected problems are returned as messages. A cancelled capture is not followed by a
     * complete load.
     */
    private static LoadedMask load(Dimension dimension, File file, BackgroundImageLoader.Cancellation cancellation) {
        CachedImageHeightMap capture = null;
        boolean handedOver = false;
        try {
            MaskImporter importer = null;
            if (MaskImporter.isBoundedEnabled()) {
                try {
                    capture = CachedImageHeightMap.capture(file, file.getName(), cancellation);
                    if (capture != null) {
                        importer = MaskImporter.bounded(dimension, file, capture);
                        if (importer == null) {
                            // Not guaranteed to import identically; the complete load below takes over
                            capture.close();
                            capture = null;
                        }
                    }
                } catch (CancellationException e) {
                    throw e;
                } catch (IOException | RuntimeException e) {
                    // The complete load below reports the problem, if it persists
                    logger.warn("Could not capture mask " + file.getName() + " with bounded memory; loading it completely", e);
                    importer = null;
                    if (capture != null) {
                        capture.close();
                        capture = null;
                    }
                }
            }
            BufferedImage image = null;
            if (capture == null) {
                cancellation.check();
                image = BackgroundImageLoader.read(file, cancellation);
                if (image == null) {
                    return LoadedMask.error("Not an image file, or damaged file!");
                } else if (image.isAlphaPremultiplied()) {
                    return LoadedMask.error("Premultiplied alpha not supported! Please convert to non-premultiplied.");
                }
                importer = new MaskImporter(dimension, file, image);
            }
            if (! importer.isSupported()) {
                return LoadedMask.error(importer.getUnsupportedReason());
            }
            handedOver = true;
            return new LoadedMask(importer, capture, image, null);
        } catch (IOException e) {
            logFailure(cancellation, "I/O error loading image " + file, e);
            return LoadedMask.error(String.format("I/O error loading image (message: %s)!", e.getMessage()));
        } catch (IllegalArgumentException e) {
            logFailure(cancellation, "IllegalArgumentException loading image " + file, e);
            if ("Invalid scanline stride".equals(e.getMessage())) {
                return LoadedMask.error("Image data too large to load; try reducing dimensions or bit depth");
            } else {
                return LoadedMask.error("Error in image data: " + e.getMessage());
            }
        } finally {
            if ((! handedOver) && (capture != null)) {
                capture.close();
            }
        }
    }

    private static void logFailure(BackgroundImageLoader.Cancellation cancellation, String message, Exception e) {
        if (cancellation.isCancelled()) {
            logger.debug(message + " (no longer wanted)", e);
        } else {
            logger.error(message, e);
        }
    }

    /** Take over a loaded mask, or show why it could not be loaded. */
    private void maskLoaded(LoadedMask loaded) {
        if (loaded.error != null) {
            labelImageDimensions.setForeground(Color.RED);
            labelImageDimensions.setText(loaded.error);
            selectedFile = null;
        } else {
            // The dialog owns the mask from here
            capture = loaded.capture;
            image = loaded.image;
            maskImporter = loaded.importer;
            labelImageDimensions.setForeground(null);
            final int width = imageWidth(), height = imageHeight(), sampleSize = sampleSize();
            maskImporter.setRemoveExistingLayer(checkBoxRemoveExisting.isSelected());
            MaskImporter.InputType inputType = maskImporter.getInputType();
            if (inputType == EIGHT_BIT_GREY_SCALE || inputType == SIXTEEN_BIT_GREY_SCALE || inputType == THIRTY_TWO_BIT_GREY_SCALE || inputType == FLOAT_GREY_SCALE || inputType == DOUBLE_GREY_SCALE) {
                SpinnerNumberModel thresholdModel = (SpinnerNumberModel) spinnerThreshold.getModel();
                thresholdModel.setMinimum(maskImporter.getImageLowValue());
                thresholdModel.setMaximum(maskImporter.getImageHighValue());
                thresholdModel.setValue((maskImporter.getImageLowValue() + maskImporter.getImageHighValue()) / 2);
                final double stepSize;
                if ((inputType == FLOAT_GREY_SCALE) || (inputType == DOUBLE_GREY_SCALE)) {
                    final double range = maskImporter.getImageHighValue() - maskImporter.getImageLowValue();
                    if (range <= 1.0) {
                        stepSize = 0.001;
                    } else if (range <= 10.0) {
                        stepSize = 0.01;
                    } else if (range <= 100.0) {
                        stepSize = 0.1;
                    } else {
                        stepSize = 1.0;
                    }
                } else {
                    stepSize = 1.0;
                }
                thresholdModel.setStepSize(stepSize);
            }
            comboBoxApplyToTerrain.setSelectedItem(null);
            if (inputType == COLOUR) {
                radioButtonLayer.setSelected(true);
                comboBoxLayer.setSelectedItem(Annotations.INSTANCE);
                comboBoxApplyToLayerValue.setSelectedItem(null);
            } else {
                buttonGroup1.clearSelection();
                comboBoxLayer.setSelectedIndex(0);
            }
            labelReason.setText(null);

            final String scalingNotSupportedReason = maskImporter.getScalingNotSupportedReason();
            if (scalingNotSupportedReason != null) {
                spinnerScale.setValue(100.0f);
                spinnerScale.setEnabled(false);
                spinnerScale.setToolTipText(scalingNotSupportedReason);
                labelImageDimensions.setIcon(ICON_WARNING);
                labelImageDimensions.setText(scalingNotSupportedReason);
            } else {
                spinnerScale.setEnabled(true);
                spinnerScale.setToolTipText(null);
                labelImageDimensions.setIcon(null);
                if (inputType == COLOUR) {
                    labelImageDimensions.setText(String.format("Image size: %,d x %,d, colour, %d bits", width, height, sampleSize));
                } else if (inputType == ONE_BIT_GREY_SCALE) {
                    labelImageDimensions.setText(String.format("Image size: %,d x %,d, black and white", width, height));
                } else if ((inputType == FLOAT_GREY_SCALE) || (inputType == DOUBLE_GREY_SCALE)) {
                    labelImageDimensions.setText(String.format("<html>Image size: %,d x %,d, grey scale, %d bits floating point<br>Lowest value: %,f, highest value: %,f</html>", width, height, sampleSize, maskImporter.getImageLowValue(), maskImporter.getImageHighValue()));
                } else {
                    labelImageDimensions.setText(String.format("<html>Image size: %,d x %,d, grey scale, %d bits integer<br>Lowest value: %,d, highest value: %,d</html>", width, height, sampleSize, Math.round(maskImporter.getImageLowValue()), Math.round(maskImporter.getImageHighValue())));
                }
            }
            if ((inputType == FLOAT_GREY_SCALE) || (inputType == DOUBLE_GREY_SCALE)) {
                labelMaskRange.setText(String.format("Actual mask range: %,f - %,f", maskImporter.getImageLowValue(), maskImporter.getImageHighValue()));
                labelMaskRange.setVisible(true);
            } else if ((inputType != ONE_BIT_GREY_SCALE) && (inputType != COLOUR) && (inputType != UNSUPPORTED)) {
                labelMaskRange.setText(String.format("Actual mask range: %,d - %,d", (long) maskImporter.getImageLowValue(), (long) maskImporter.getImageHighValue()));
                labelMaskRange.setVisible(true);
            } else {
                labelMaskRange.setVisible(false);
            }
            updateWorldDimensions();
        }
        setControlStates();
    }

    /** Report an unexpected failure to load the mask. */
    private void maskLoadFailed(Throwable failure) {
        selectedFile = null;
        labelImageDimensions.setForeground(Color.RED);
        if (failure instanceof OutOfMemoryError) {
            logger.error("Out of memory loading image", failure);
            labelImageDimensions.setText("Image data too large to load; try reducing dimensions or bit depth");
        } else if (failure instanceof RejectedExecutionException) {
            labelImageDimensions.setText("Too many images are being loaded; please select the file again");
        } else {
            labelImageDimensions.setText("Error loading image!");
            ExceptionHandler.handleException(failure, this);
        }
        setControlStates();
    }

    private void updateWorldDimensions() {
        float scale = (float) spinnerScale.getValue();
        labelWorldDimensions.setText(String.format("Scaled size: %,d x %,d blocks", Math.round(imageWidth() * (scale / 100)), Math.round(imageHeight() * (scale / 100))));
    }

    private boolean hasImage() {
        return (image != null) || (capture != null);
    }

    private int imageWidth() {
        final CachedImageHeightMap capture = this.capture;
        return (capture != null) ? capture.getWidth() : image.getWidth();
    }

    private int imageHeight() {
        final CachedImageHeightMap capture = this.capture;
        return (capture != null) ? capture.getHeight() : image.getHeight();
    }

    private int sampleSize() {
        final CachedImageHeightMap capture = this.capture;
        return (capture != null) ? capture.getBitDepth() : image.getSampleModel().getSampleSize(0);
    }

    /** Close the bounded capture of the mask, if any, deleting its cache file. */
    private void releaseCapture() {
        final CachedImageHeightMap capture = this.capture;
        this.capture = null;
        if (capture != null) {
            capture.close();
        }
    }

    @Override
    public void dispose() {
        // Nothing is loaded after the dialog closes; a load still in progress is discarded when it returns
        loader.close();
        super.dispose();
        // Nothing imports the mask after the dialog closes: cancelled in any way, including closing the window, or
        // after ok(), whose import released the capture already
        releaseCapture();
        image = null;
        maskImporter = null;
    }

    @Override
    protected void ok() {
        if ((maskImporter == null) || loader.isLoading()) {
            return;
        }
        maskImporter.setThreshold((double) spinnerThreshold.getValue());
        maskImporter.setMapping((Mapping) comboBoxMapping.getSelectedItem());
        maskImporter.setRemoveExistingLayer(radioButtonLayer.isSelected() && checkBoxRemoveExisting.isSelected());
        maskImporter.setScale((float) spinnerScale.getValue() / 100);
        maskImporter.setxOffset((Integer) spinnerOffsetX.getValue());
        maskImporter.setyOffset((Integer) spinnerOffsetY.getValue());
        try {
            try {
                ProgressDialog.executeTask(this, new ProgressTask<Void>() {
                    @Override
                    public String getName() {
                        return "Importing mask";
                    }

                    @Override
                    public Void execute(ProgressReceiver progressReceiver) throws ProgressReceiver.OperationCancelled {
                        maskImporter.doImport(progressReceiver);
                        return null;
                    }
                });
            } finally {
                // The import is the last consumer of the captured mask, whether it completed, failed or was cancelled
                releaseCapture();
            }
            Configuration.getInstance().setMasksDirectory(selectedFile.getParentFile());
            dimension.armSavePoint();
            super.ok();
        } catch (RuntimeException e) {
            ExceptionHandler.handleException(e, this);
            cancel();
        }
    }

    private void selectFile() {
        File myHeightMapDir = masksDir;
        if (myHeightMapDir == null) {
            myHeightMapDir = Configuration.getInstance().getMasksDirectory();
        }
        if (myHeightMapDir == null) {
            myHeightMapDir = Configuration.getInstance().getHeightMapsDirectory();
        }
        final File file = ImageUtils.selectImageForOpen(this, "a mask image file", myHeightMapDir);
        if (file != null) {
            masksDir = file.getParentFile();
            fieldFilename.setText(file.getAbsolutePath());
        }
    }

    /** This method is called from within the constructor to
     * initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is
     * always regenerated by the Form Editor.
     */
    @SuppressWarnings({"Convert2Lambda", "Anonymous2MethodRef"}) // Managed by NetBeans
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jLabel14 = new javax.swing.JLabel();
        buttonGroup1 = new javax.swing.ButtonGroup();
        jLabel1 = new javax.swing.JLabel();
        fieldFilename = new javax.swing.JTextField();
        buttonSelectFile = new javax.swing.JButton();
        labelImageDimensions = new javax.swing.JLabel();
        buttonCancel = new javax.swing.JButton();
        buttonOk = new javax.swing.JButton();
        jLabel2 = new javax.swing.JLabel();
        radioButtonTerrain = new javax.swing.JRadioButton();
        radioButtonLayer = new javax.swing.JRadioButton();
        comboBoxLayer = new javax.swing.JComboBox<>();
        jLabel3 = new javax.swing.JLabel();
        spinnerThreshold = new javax.swing.JSpinner();
        jLabel4 = new javax.swing.JLabel();
        spinnerScale = new javax.swing.JSpinner();
        jLabel5 = new javax.swing.JLabel();
        jLabel11 = new javax.swing.JLabel();
        spinnerOffsetX = new javax.swing.JSpinner();
        spinnerOffsetY = new javax.swing.JSpinner();
        jLabel12 = new javax.swing.JLabel();
        labelWorldDimensions = new javax.swing.JLabel();
        checkBoxRemoveExisting = new javax.swing.JCheckBox();
        comboBoxApplyToTerrain = new javax.swing.JComboBox<>();
        jLabel10 = new javax.swing.JLabel();
        comboBoxApplyToLayerValue = new javax.swing.JComboBox<>();
        labelReason = new javax.swing.JLabel();
        comboBoxMapping = new javax.swing.JComboBox<>();
        jLabel6 = new javax.swing.JLabel();
        labelMaskRange = new javax.swing.JLabel();

        jLabel14.setText("jLabel14");

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("Import Mask");

        jLabel1.setText("Select the image to import as a mask:");

        buttonSelectFile.setText("...");
        buttonSelectFile.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonSelectFileActionPerformed(evt);
            }
        });

        labelImageDimensions.setText("<html>Image size: ? x ?, bit depth: ?<br>\nLowest value: ?, highest value: ?</html>");

        buttonCancel.setText("Cancel");
        buttonCancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonCancelActionPerformed(evt);
            }
        });

        buttonOk.setText("OK");
        buttonOk.setEnabled(false);
        buttonOk.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonOkActionPerformed(evt);
            }
        });

        jLabel2.setText("Apply to:");

        buttonGroup1.add(radioButtonTerrain);
        radioButtonTerrain.setText("terrain:");
        radioButtonTerrain.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                radioButtonTerrainActionPerformed(evt);
            }
        });

        buttonGroup1.add(radioButtonLayer);
        radioButtonLayer.setText("layer");
        radioButtonLayer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                radioButtonLayerActionPerformed(evt);
            }
        });

        comboBoxLayer.setEnabled(false);
        comboBoxLayer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                comboBoxLayerActionPerformed(evt);
            }
        });

        jLabel3.setText("Select the mapping:");

        spinnerThreshold.setModel(new javax.swing.SpinnerNumberModel(0.0d, 0.0d, 255.0d, 1.0d));
        spinnerThreshold.setEnabled(false);

        jLabel4.setText("Scale:");

        spinnerScale.setModel(new javax.swing.SpinnerNumberModel(Float.valueOf(100.0f), Float.valueOf(0.01f), Float.valueOf(999.99f), Float.valueOf(0.1f)));
        spinnerScale.setEditor(new javax.swing.JSpinner.NumberEditor(spinnerScale, "0.00"));
        spinnerScale.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                spinnerScaleStateChanged(evt);
            }
        });

        jLabel5.setText("%");

        jLabel11.setText("Offset:");
        jLabel11.setToolTipText("The origin of the height map will be at these coordinates in the map");

        spinnerOffsetX.setModel(new javax.swing.SpinnerNumberModel(0, -999999, 999999, 1));
        spinnerOffsetX.setToolTipText("The origin of the height map will be at these coordinates in the map");
        spinnerOffsetX.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                spinnerOffsetXStateChanged(evt);
            }
        });

        spinnerOffsetY.setModel(new javax.swing.SpinnerNumberModel(0, -999999, 999999, 1));
        spinnerOffsetY.setToolTipText("The origin of the height map will be at these coordinates in the map");
        spinnerOffsetY.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                spinnerOffsetYStateChanged(evt);
            }
        });

        jLabel12.setText(",");
        jLabel12.setToolTipText("The origin of the height map will be at these coordinates in the map");

        labelWorldDimensions.setText("Scaled size: ? x ? blocks");

        checkBoxRemoveExisting.setText("remove existing layer");
        checkBoxRemoveExisting.setEnabled(false);

        comboBoxApplyToTerrain.setEnabled(false);
        comboBoxApplyToTerrain.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                comboBoxApplyToTerrainActionPerformed(evt);
            }
        });

        jLabel10.setText(": ");

        comboBoxApplyToLayerValue.setEnabled(false);
        comboBoxApplyToLayerValue.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                comboBoxApplyToLayerValueActionPerformed(evt);
            }
        });

        labelReason.setFont(labelReason.getFont().deriveFont((labelReason.getFont().getStyle() | java.awt.Font.ITALIC)));

        comboBoxMapping.setEnabled(false);
        comboBoxMapping.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                comboBoxMappingActionPerformed(evt);
            }
        });

        jLabel6.setText("Threshold:");

        labelMaskRange.setText("Actual mask range: -999,999 - 999,999");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addGap(10, 10, 10)
                        .addComponent(jLabel6)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(spinnerThreshold, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(18, 18, 18)
                        .addComponent(labelMaskRange)
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                                .addComponent(fieldFilename)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(buttonSelectFile))
                            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                                .addGap(0, 0, Short.MAX_VALUE)
                                .addComponent(labelReason)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(buttonOk)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(buttonCancel))
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(jLabel1)
                                    .addComponent(labelImageDimensions, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addGroup(layout.createSequentialGroup()
                                        .addComponent(jLabel4)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(spinnerScale, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addGap(0, 0, 0)
                                        .addComponent(jLabel5)
                                        .addGap(18, 18, 18)
                                        .addComponent(jLabel11)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(spinnerOffsetX, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addGap(0, 0, 0)
                                        .addComponent(jLabel12)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(spinnerOffsetY, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                    .addComponent(labelWorldDimensions)
                                    .addGroup(layout.createSequentialGroup()
                                        .addComponent(jLabel2)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                            .addGroup(layout.createSequentialGroup()
                                                .addComponent(radioButtonTerrain)
                                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(comboBoxApplyToTerrain, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                            .addGroup(layout.createSequentialGroup()
                                                .addComponent(radioButtonLayer)
                                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(comboBoxLayer, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(jLabel10)
                                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                                .addComponent(comboBoxApplyToLayerValue, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                                .addGap(18, 18, 18)
                                                .addComponent(checkBoxRemoveExisting))))
                                    .addGroup(layout.createSequentialGroup()
                                        .addComponent(jLabel3)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(comboBoxMapping, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                                .addGap(0, 0, Short.MAX_VALUE)))
                        .addContainerGap())))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel1)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(fieldFilename, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(buttonSelectFile))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(labelImageDimensions, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel4)
                    .addComponent(spinnerScale, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel5)
                    .addComponent(jLabel11)
                    .addComponent(spinnerOffsetX, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel12)
                    .addComponent(spinnerOffsetY, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(labelWorldDimensions)
                .addGap(18, 18, 18)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel2)
                    .addComponent(radioButtonTerrain)
                    .addComponent(comboBoxApplyToTerrain, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(radioButtonLayer)
                    .addComponent(comboBoxLayer, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(checkBoxRemoveExisting)
                    .addComponent(jLabel10)
                    .addComponent(comboBoxApplyToLayerValue, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(18, 18, 18)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel3)
                    .addComponent(comboBoxMapping, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel6)
                    .addComponent(spinnerThreshold, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(labelMaskRange))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(buttonCancel)
                    .addComponent(buttonOk)
                    .addComponent(labelReason))
                .addContainerGap())
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void buttonCancelActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonCancelActionPerformed
        cancel();
    }//GEN-LAST:event_buttonCancelActionPerformed

    private void buttonSelectFileActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonSelectFileActionPerformed
        selectFile();
    }//GEN-LAST:event_buttonSelectFileActionPerformed

    private void buttonOkActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonOkActionPerformed
        ok();
    }//GEN-LAST:event_buttonOkActionPerformed

    private void spinnerScaleStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_spinnerScaleStateChanged
        if (hasImage()) {
            updateWorldDimensions();
        }
    }//GEN-LAST:event_spinnerScaleStateChanged

    private void spinnerOffsetXStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_spinnerOffsetXStateChanged
        if (hasImage()) {
            updateWorldDimensions();
        }
    }//GEN-LAST:event_spinnerOffsetXStateChanged

    private void spinnerOffsetYStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_spinnerOffsetYStateChanged
        if (hasImage()) {
            updateWorldDimensions();
        }
    }//GEN-LAST:event_spinnerOffsetYStateChanged

    private void radioButtonTerrainActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_radioButtonTerrainActionPerformed
        if (radioButtonTerrain.isSelected()) {
            maskImporter.setApplyToTerrain(true);
            comboBoxApplyToLayerValue.setModel(new DefaultComboBoxModel<>());
            comboBoxApplyToLayerValue.setSelectedItem(null);
        }
        updatePossibleMappings();
        setControlStates();
    }//GEN-LAST:event_radioButtonTerrainActionPerformed

    private void radioButtonLayerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_radioButtonLayerActionPerformed
        if (radioButtonLayer.isSelected()) {
            maskImporter.setApplyToLayer((Layer) comboBoxLayer.getSelectedItem());
            loadLayerValues();
        }
        updatePossibleMappings();
        setControlStates();
    }//GEN-LAST:event_radioButtonLayerActionPerformed

    private void comboBoxLayerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_comboBoxLayerActionPerformed
        maskImporter.setApplyToLayer((Layer) comboBoxLayer.getSelectedItem());
        loadLayerValues();
        updatePossibleMappings();
        setControlStates();
    }//GEN-LAST:event_comboBoxLayerActionPerformed

    private void comboBoxApplyToTerrainActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_comboBoxApplyToTerrainActionPerformed
        maskImporter.setApplyToTerrainType((Terrain) comboBoxApplyToTerrain.getSelectedItem());
        updatePossibleMappings();
        setControlStates();
    }//GEN-LAST:event_comboBoxApplyToTerrainActionPerformed

    private void comboBoxApplyToLayerValueActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_comboBoxApplyToLayerValueActionPerformed
        maskImporter.setApplyToLayerValue((Integer) comboBoxApplyToLayerValue.getSelectedItem());
        updatePossibleMappings();
        setControlStates();
    }//GEN-LAST:event_comboBoxApplyToLayerValueActionPerformed

    private void comboBoxMappingActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_comboBoxMappingActionPerformed
        setControlStates();
    }//GEN-LAST:event_comboBoxMappingActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton buttonCancel;
    private javax.swing.ButtonGroup buttonGroup1;
    private javax.swing.JButton buttonOk;
    private javax.swing.JButton buttonSelectFile;
    private javax.swing.JCheckBox checkBoxRemoveExisting;
    private javax.swing.JComboBox<Integer> comboBoxApplyToLayerValue;
    private javax.swing.JComboBox<Terrain> comboBoxApplyToTerrain;
    private javax.swing.JComboBox<Layer> comboBoxLayer;
    private javax.swing.JComboBox<Mapping> comboBoxMapping;
    private javax.swing.JTextField fieldFilename;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel11;
    private javax.swing.JLabel jLabel12;
    private javax.swing.JLabel jLabel14;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel labelImageDimensions;
    private javax.swing.JLabel labelMaskRange;
    private javax.swing.JLabel labelReason;
    private javax.swing.JLabel labelWorldDimensions;
    private javax.swing.JRadioButton radioButtonLayer;
    private javax.swing.JRadioButton radioButtonTerrain;
    private javax.swing.JSpinner spinnerOffsetX;
    private javax.swing.JSpinner spinnerOffsetY;
    private javax.swing.JSpinner spinnerScale;
    private javax.swing.JSpinner spinnerThreshold;
    // End of variables declaration//GEN-END:variables

    private final Dimension dimension;
    private final ColourScheme colourScheme;
    private final List<Layer> allLayers;
    private final CustomBiomeManager customBiomeManager;
    private File selectedFile, masksDir;
    private volatile BufferedImage image;
    /** The bounded capture of the mask instead of {@link #image}, when enabled and supported. Owned by this dialog. */
    private volatile CachedImageHeightMap capture;
    private MaskImporter maskImporter;
    /** Loads the mask off the event dispatch thread; a result belongs to it until {@link #maskLoaded(LoadedMask)}. */
    private final BackgroundImageLoader<LoadedMask> loader = new BackgroundImageLoader<>(LoadedMask::release);

    /** A mask loaded off the event dispatch thread: an importer of a capture or of an image in memory, or an error. */
    private static final class LoadedMask {
        LoadedMask(MaskImporter importer, CachedImageHeightMap capture, BufferedImage image, String error) {
            this.importer = importer;
            this.capture = capture;
            this.image = image;
            this.error = error;
        }

        static LoadedMask error(String message) {
            return new LoadedMask(null, null, null, message);
        }

        /** Release a mask which is not delivered to the dialog. */
        void release() {
            if (capture != null) {
                capture.close();
            }
        }

        final MaskImporter importer;
        final CachedImageHeightMap capture;
        final BufferedImage image;
        final String error;
    }

    private static final Icon ICON_WARNING = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/error.png");
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ImportMaskDialog.class);
    private static final long serialVersionUID = 1L;
}