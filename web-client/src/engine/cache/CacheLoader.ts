// Game cache loader for OSRS assets
// Based on the cache format used by 2009scape

export interface CacheIndex {
  id: number;
  name: string;
  files: number;
}

export interface CacheFile {
  indexId: number;
  fileId: number;
  data: ArrayBuffer;
}

export interface ModelDefinition {
  id: number;
  vertices: Float32Array;
  indices: Uint16Array;
  texCoords?: Float32Array;
  colors?: Uint8Array;
}

export interface AnimationDefinition {
  id: number;
  frameCount: number;
  frameDuration: number;
  frames: AnimationFrame[];
}

export interface AnimationFrame {
  transformations: number[];
}

export interface TextureDefinition {
  id: number;
  width: number;
  height: number;
  pixels: Uint8Array;
}

export interface MapRegion {
  regionX: number;
  regionY: number;
  tiles: MapTile[][];
  objects: MapObject[];
}

export interface MapTile {
  height: number;
  overlayId: number;
  underlayId: number;
  flags: number;
}

export interface MapObject {
  id: number;
  x: number;
  y: number;
  z: number;
  type: number;
  rotation: number;
}

export class CacheLoader {
  private baseUrl: string;
  private indices: Map<number, CacheIndex> = new Map();
  private fileCache: Map<string, ArrayBuffer> = new Map();
  private modelCache: Map<number, ModelDefinition> = new Map();
  private textureCache: Map<number, TextureDefinition> = new Map();
  private mapCache: Map<string, MapRegion> = new Map();

  constructor(baseUrl: string = '/cache') {
    this.baseUrl = baseUrl;
  }

  async initialize(): Promise<void> {
    // Load cache indices
    const response = await fetch(`${this.baseUrl}/indices.json`);
    const indices: CacheIndex[] = await response.json();

    for (const index of indices) {
      this.indices.set(index.id, index);
    }

    console.log(`Loaded ${indices.length} cache indices`);
  }

  async loadFile(indexId: number, fileId: number): Promise<ArrayBuffer> {
    const cacheKey = `${indexId}_${fileId}`;

    if (this.fileCache.has(cacheKey)) {
      return this.fileCache.get(cacheKey)!;
    }

    const response = await fetch(`${this.baseUrl}/${indexId}/${fileId}.dat`);
    if (!response.ok) {
      throw new Error(`Failed to load cache file: ${indexId}/${fileId}`);
    }

    const data = await response.arrayBuffer();
    this.fileCache.set(cacheKey, data);

    return data;
  }

  async loadModel(modelId: number): Promise<ModelDefinition> {
    if (this.modelCache.has(modelId)) {
      return this.modelCache.get(modelId)!;
    }

    // Models are typically in index 7
    const data = await this.loadFile(7, modelId);
    const model = this.parseModel(modelId, data);
    this.modelCache.set(modelId, model);

    return model;
  }

  private parseModel(id: number, data: ArrayBuffer): ModelDefinition {
    const view = new DataView(data);
    let offset = 0;

    // Simplified model parsing - actual format is more complex
    const vertexCount = view.getUint16(offset, false);
    offset += 2;
    const faceCount = view.getUint16(offset, false);
    offset += 2;

    const vertices = new Float32Array(vertexCount * 3);
    const indices = new Uint16Array(faceCount * 3);

    // Read vertices
    for (let i = 0; i < vertexCount * 3; i++) {
      vertices[i] = view.getInt16(offset, false);
      offset += 2;
    }

    // Read face indices
    for (let i = 0; i < faceCount * 3; i++) {
      indices[i] = view.getUint16(offset, false);
      offset += 2;
    }

    return { id, vertices, indices };
  }

  async loadTexture(textureId: number): Promise<TextureDefinition> {
    if (this.textureCache.has(textureId)) {
      return this.textureCache.get(textureId)!;
    }

    // Textures are typically in index 9
    const data = await this.loadFile(9, textureId);
    const texture = this.parseTexture(textureId, data);
    this.textureCache.set(textureId, texture);

    return texture;
  }

  private parseTexture(id: number, data: ArrayBuffer): TextureDefinition {
    const view = new DataView(data);

    const width = view.getUint16(0, false);
    const height = view.getUint16(2, false);
    const pixels = new Uint8Array(data, 4);

    return { id, width, height, pixels };
  }

  async loadMapRegion(regionX: number, regionY: number): Promise<MapRegion> {
    const cacheKey = `${regionX}_${regionY}`;

    if (this.mapCache.has(cacheKey)) {
      return this.mapCache.get(cacheKey)!;
    }

    // Map data is in index 5
    const terrainId = (regionX << 8) | regionY;
    const data = await this.loadFile(5, terrainId);
    const region = this.parseMapRegion(regionX, regionY, data);
    this.mapCache.set(cacheKey, region);

    return region;
  }

  private parseMapRegion(regionX: number, regionY: number, data: ArrayBuffer): MapRegion {
    const view = new DataView(data);
    const tiles: MapTile[][] = [];
    const objects: MapObject[] = [];

    // Initialize 64x64 tile grid
    for (let x = 0; x < 64; x++) {
      tiles[x] = [];
      for (let y = 0; y < 64; y++) {
        tiles[x][y] = {
          height: 0,
          overlayId: 0,
          underlayId: 0,
          flags: 0,
        };
      }
    }

    // Parse tile data (simplified)
    let offset = 0;
    for (let z = 0; z < 4; z++) {
      for (let x = 0; x < 64; x++) {
        for (let y = 0; y < 64; y++) {
          if (offset + 4 <= data.byteLength) {
            tiles[x][y].height = view.getInt16(offset, false);
            tiles[x][y].overlayId = view.getUint8(offset + 2);
            tiles[x][y].underlayId = view.getUint8(offset + 3);
            offset += 4;
          }
        }
      }
    }

    return { regionX, regionY, tiles, objects };
  }

  async loadItemDefinition(itemId: number): Promise<object> {
    // Items are in index 2
    const data = await this.loadFile(2, itemId);
    return this.parseItemDefinition(data);
  }

  private parseItemDefinition(data: ArrayBuffer): object {
    // Simplified item parsing
    const view = new DataView(data);
    return {
      name: 'Unknown',
      value: view.getInt32(0, false),
      stackable: view.getUint8(4) === 1,
    };
  }

  async loadNPCDefinition(npcId: number): Promise<object> {
    // NPCs are in index 2
    const data = await this.loadFile(2, npcId + 10000);
    return this.parseNPCDefinition(data);
  }

  private parseNPCDefinition(data: ArrayBuffer): object {
    const view = new DataView(data);
    return {
      name: 'Unknown NPC',
      combatLevel: view.getUint16(0, false),
      modelIds: [],
    };
  }

  clearCache(): void {
    this.fileCache.clear();
    this.modelCache.clear();
    this.textureCache.clear();
    this.mapCache.clear();
  }

  getCacheStats(): { files: number; models: number; textures: number; maps: number } {
    return {
      files: this.fileCache.size,
      models: this.modelCache.size,
      textures: this.textureCache.size,
      maps: this.mapCache.size,
    };
  }
}

// Singleton instance
let loaderInstance: CacheLoader | null = null;

export const getCacheLoader = (): CacheLoader => {
  if (!loaderInstance) {
    loaderInstance = new CacheLoader();
  }
  return loaderInstance;
};
