package org.pepsoft.worldpainter;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.plugins.WPPluginManager;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Exercises the actual material editor in an isolated application configuration. */
public class CustomMaterialPreviewIntegrationTest {
    private static App app;
    private static Configuration previous;
    @BeforeClass public static void start() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        String root=System.getProperty("welt.docking.configRoot");assumeNotNull(root);
        assertTrue(Configuration.getConfigDir().toPath().toAbsolutePath().normalize().startsWith(new java.io.File(root).toPath().toAbsolutePath().normalize()));
        previous=Configuration.getInstance();
        Configuration config=new Configuration();config.setDefaultWidth(2);config.setDefaultHeight(2);config.setAutosaveEnabled(false);Configuration.setInstance(config);
        WPPluginManager.initialise(config.getUuid(),WPContext.INSTANCE);
        var world=WorldFactory.createDefaultWorld(config,1234L);
        SwingUtilities.invokeAndWait(() -> { app=App.getInstance();app.setWorld(world,true); });
    }
    @AfterClass public static void stop() throws Exception {
        if(app!=null) {
            SwingUtilities.invokeAndWait(() -> {app.clearWorld();app.getDockingManager().close();app.dispose();});
            SwingUtilities.invokeAndWait(() -> {});Configuration.setInstance(previous);
        }
    }
    @Test public void actualDialogUsesOneNativeTransactionAndDisplaysEquivalentPixels() throws Exception {
        AtomicReference<Throwable> error=new AtomicReference<>();
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.materialPreview");
        try {
            System.setProperty(Native.GEN_KEY,"true");System.setProperty("welt.native.materialPreview","true");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    MixedMaterial blobs=new MixedMaterial("Blobs preview",new MixedMaterial.Row[]{new MixedMaterial.Row(org.pepsoft.minecraft.Material.STONE,600,1f),new MixedMaterial.Row(org.pepsoft.minecraft.Material.DIRT,400,0.7f)},-1,null,1f);
                    MixedMaterial layered=new MixedMaterial("Layered preview",blobs.getRows(),-1,null,new NoiseSettings(0,17,3,1.2f),-0.7,0,true);
                    for(MixedMaterial source:new MixedMaterial[]{blobs,layered}) {
                        CustomMaterialDialog dialog=new CustomMaterialDialog(app,DefaultPlugin.JAVA_ANVIL_1_18,source,false,ColourScheme.DEFAULT);
                        try {
                            Field field=CustomMaterialDialog.class.getDeclaredField("labelPreview");field.setAccessible(true);
                            JLabel label=(JLabel)field.get(dialog);label.setSize(129,93);
                            Method model=CustomMaterialDialog.class.getDeclaredMethod("getNewMaterial");model.setAccessible(true);
                            MixedMaterial actualModel=(MixedMaterial)model.invoke(dialog);
                            System.setProperty(Native.GEN_KEY,"false");BufferedImage expected=actualModel.createPreview(129,93,ColourScheme.DEFAULT);
                            System.setProperty(Native.GEN_KEY,"true");
                            long before=MaterialPreviewAccess.nativeTransactions();
                            Method update=CustomMaterialDialog.class.getDeclaredMethod("updatePreview");update.setAccessible(true);update.invoke(dialog);
                            assertEquals(before+1,MaterialPreviewAccess.nativeTransactions());
                            BufferedImage actual=(BufferedImage)((ImageIcon)label.getIcon()).getImage();
                            for(int y=0;y<93;y++)for(int x=0;x<129;x++)assertEquals(expected.getRGB(x,y),actual.getRGB(x,y));
                        } finally {dialog.dispose();}
                    }
                } catch(Throwable failure) {error.set(failure);}
            });
            if(error.get()!=null)throw new AssertionError(error.get());
        } finally {if(gen==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,gen);if(flag==null)System.clearProperty("welt.native.materialPreview");else System.setProperty("welt.native.materialPreview",flag);}
    }
}
