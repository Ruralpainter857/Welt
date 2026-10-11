package org.pepsoft.worldpainter.objects;

import org.pepsoft.minecraft.Entity;
import org.pepsoft.minecraft.Material;
import org.pepsoft.minecraft.TileEntity;

import javax.vecmath.Point3i;
import java.io.*;
import java.util.*;

import static org.pepsoft.util.CollectionUtils.copyOf;

/**
 * A generic {@link WPObject}, not tied to any particular platform or file
 * format.
 *
 * Created by Pepijn on 9-3-2017.
 */
public final class GenericObject extends NamedObjectWithAttributes {
    public GenericObject(String name, int dimX, int dimY, int dimZ, Material[] data) {
        this(name, dimX, dimY, dimZ, data, null, null, null);
    }

    public GenericObject(String name, int dimX, int dimY, int dimZ, Material[] data, List<Entity> entities, List<TileEntity> tileEntities, Map<String, Serializable> attributes) {
        super(name, ((attributes != null) && (! attributes.isEmpty())) ? new HashMap<>(attributes) : null);
        this.dimX = dimX;
        this.dimY = dimY;
        this.dimZ = dimZ;
        compact = CompactMaterialVolume.capture(data);
        this.data = compact == null ? Arrays.copyOf(data, data.length) : null;
        this.entities = ((entities != null) && (! entities.isEmpty())) ? new ArrayList<>(entities) : null;
        this.tileEntities = ((tileEntities != null) && (! tileEntities.isEmpty())) ? new ArrayList<>(tileEntities) : null;
        if ((dimX * dimY * dimZ) != data.length) {
            throw new IllegalArgumentException();
        }
    }

    public GenericObject(WPObject object) {
        super(object.getName(), ((object.getAttributes() != null) && (! object.getAttributes().isEmpty())) ? new HashMap<>(object.getAttributes()) : null);
        Point3i dims = object.getDimensions();
        this.dimX = dims.x;
        this.dimY = dims.y;
        this.dimZ = dims.z;
        // Immutable snapshots can share storage without reading source blocks again.
        if (object instanceof GenericObject && ((GenericObject) object).compact != null) {
            compact = ((GenericObject) object).compact;
            data = null;
        } else if (object instanceof CompactObjectSource &&
                (compact = ((CompactObjectSource) object).copyCompactVolume()) != null) {
            data = null;
        } else {
            Material[] captured = new Material[dimX * dimY * dimZ];
            // Preserve the original traversal, including random material selection order.
            for (int x = 0; x < dimX; x++) for (int y = 0; y < dimY; y++) for (int z = 0; z < dimZ; z++) {
                if (object.getMask(x, y, z)) captured[x + y * dimX + z * dimX * dimY] = object.getMaterial(x, y, z);
            }
            compact = CompactMaterialVolume.capture(captured);
            data = compact == null ? captured : null;
        }
        this.entities = copyOf(object.getEntities());
        this.tileEntities = copyOf(object.getTileEntities());
    }

    @Override
    public Point3i getDimensions() {
        return new Point3i(dimX, dimY, dimZ);
    }

    @Override
    public Material getMaterial(int x, int y, int z) {
        int index = x + y * dimX + z * dimX * dimY;
        return compact == null ? data[index] : compact.get(index);
    }

    @Override
    public boolean getMask(int x, int y, int z) {
        return getMaterial(x, y, z) != null;
    }

    @Override
    public List<Entity> getEntities() {
        return entities;
    }

    @Override
    public List<TileEntity> getTileEntities() {
        return tileEntities;
    }

    private final int dimX, dimY, dimZ;
    // Keep the historical field type and serial UID for existing project readers.
    private Material[] data;
    private transient CompactMaterialVolume compact;

    private void writeObject(ObjectOutputStream out) throws IOException {
        ObjectOutputStream.PutField fields = out.putFields();
        fields.put("dimX", dimX); fields.put("dimY", dimY); fields.put("dimZ", dimZ);
        fields.put("data", compact == null ? data : compact.expand());
        fields.put("entities", entities); fields.put("tileEntities", tileEntities);
        out.writeFields();
    }
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (data != null) {
            compact = CompactMaterialVolume.capture(data);
            if (compact != null) data = null;
        }
    }
    private final List<Entity> entities;
    private final List<TileEntity> tileEntities;

    private static final long serialVersionUID = 1L;
}