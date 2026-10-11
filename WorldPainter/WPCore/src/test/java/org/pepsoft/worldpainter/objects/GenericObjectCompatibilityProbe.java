package org.pepsoft.worldpainter.objects;

import org.pepsoft.minecraft.Material;
import java.io.*;
import java.nio.file.*;

/** Run in separate JVMs with either the historical or current GenericObject class. */
public final class GenericObjectCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("write|read file");
        boolean historical=Boolean.getBoolean("welt.benchmark.expectHistorical");
        boolean compactField;
        try{GenericObject.class.getDeclaredField("compact");compactField=true;}catch(NoSuchFieldException expected){compactField=false;}
        if(historical==compactField)throw new AssertionError("Unexpected GenericObject version loaded");
        Path file=Path.of(args[1]);
        if(args[0].equals("write")){
            GenericObject object=new GenericObject("Compatibility",3,2,1,new Material[]{null,Material.AIR,Material.STONE,Material.get("welt:custom","facing","north"),null,Material.AIR});
            try(ObjectOutputStream out=new ObjectOutputStream(Files.newOutputStream(file))){out.writeObject(object);}
        }else if(args[0].equals("read")){
            GenericObject object;try(ObjectInputStream in=new ObjectInputStream(Files.newInputStream(file))){object=(GenericObject)in.readObject();}
            Material[] expected={null,Material.AIR,Material.STONE,Material.get("welt:custom","facing","north"),null,Material.AIR};
            if(!object.getName().equals("Compatibility") || object.getDimensions().x!=3 || object.getDimensions().y!=2 || object.getDimensions().z!=1)throw new AssertionError("Metadata differs");
            for(int i=0;i<expected.length;i++)if(object.getMaterial(i%3,i/3,0)!=expected[i] || object.getMask(i%3,i/3,0)!=(expected[i]!=null))throw new AssertionError("Block differs");
        }else throw new IllegalArgumentException("Unknown operation");
        System.out.println("Project compatibility "+args[0]+" passed");
    }
}