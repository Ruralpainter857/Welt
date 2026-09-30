package org.pepsoft.worldpainter;

public enum TerrainHeightOperation {
    SET(0), RAISE_TO(1), RAISE_BY(2), LOWER_TO(3), LOWER_BY(4);
    final int wireCode;
    TerrainHeightOperation(int wireCode) { this.wireCode = wireCode; }
}
