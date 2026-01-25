import * as THREE from 'three';

export interface RenderConfig {
  antialias: boolean;
  shadowsEnabled: boolean;
  maxFPS: number;
}

const defaultConfig: RenderConfig = {
  antialias: true,
  shadowsEnabled: true,
  maxFPS: 60,
};

export class GameRenderer {
  private canvas: HTMLCanvasElement;
  private renderer: THREE.WebGLRenderer;
  private scene: THREE.Scene;
  private camera: THREE.PerspectiveCamera;
  private config: RenderConfig;

  // Camera control
  private cameraTarget: THREE.Vector3;
  private cameraOffset: THREE.Vector3;
  private cameraZoom: number = 1;
  private cameraRotation: number = 0;
  private cameraPitch: number = 0.4;

  // Game objects
  private players: Map<string, THREE.Object3D> = new Map();
  private npcs: Map<number, THREE.Object3D> = new Map();
  private groundItems: Map<string, THREE.Object3D> = new Map();
  private terrain: THREE.Group | null = null;

  // Lighting
  private ambientLight: THREE.AmbientLight;
  private directionalLight: THREE.DirectionalLight;

  constructor(canvas: HTMLCanvasElement, config: Partial<RenderConfig> = {}) {
    this.canvas = canvas;
    this.config = { ...defaultConfig, ...config };

    // Initialize Three.js renderer
    this.renderer = new THREE.WebGLRenderer({
      canvas,
      antialias: this.config.antialias,
      alpha: false,
    });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.setSize(window.innerWidth, window.innerHeight);
    this.renderer.shadowMap.enabled = this.config.shadowsEnabled;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;

    // Initialize scene
    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(0x87ceeb); // Sky blue
    this.scene.fog = new THREE.Fog(0x87ceeb, 50, 200);

    // Initialize camera
    this.camera = new THREE.PerspectiveCamera(
      60,
      window.innerWidth / window.innerHeight,
      0.1,
      1000
    );
    this.cameraTarget = new THREE.Vector3(0, 0, 0);
    this.cameraOffset = new THREE.Vector3(0, 30, 30);

    // Initialize lighting
    this.ambientLight = new THREE.AmbientLight(0xffffff, 0.6);
    this.scene.add(this.ambientLight);

    this.directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
    this.directionalLight.position.set(50, 100, 50);
    this.directionalLight.castShadow = this.config.shadowsEnabled;
    this.directionalLight.shadow.mapSize.width = 2048;
    this.directionalLight.shadow.mapSize.height = 2048;
    this.directionalLight.shadow.camera.near = 0.5;
    this.directionalLight.shadow.camera.far = 500;
    this.scene.add(this.directionalLight);
  }

  initialize(): void {
    // Add ground plane (temporary - will be replaced with actual terrain)
    const groundGeometry = new THREE.PlaneGeometry(1000, 1000);
    const groundMaterial = new THREE.MeshLambertMaterial({ color: 0x4a7c4e });
    const ground = new THREE.Mesh(groundGeometry, groundMaterial);
    ground.rotation.x = -Math.PI / 2;
    ground.receiveShadow = true;
    this.scene.add(ground);

    // Add grid helper for development
    const gridHelper = new THREE.GridHelper(100, 100, 0x000000, 0x444444);
    gridHelper.position.y = 0.01;
    this.scene.add(gridHelper);

    // Initialize local player placeholder
    this.createPlayerModel('local');
  }

  createPlayerModel(id: string): THREE.Object3D {
    // Placeholder player model (box for now, will be replaced with actual model)
    const geometry = new THREE.BoxGeometry(1, 2, 1);
    const material = new THREE.MeshLambertMaterial({
      color: id === 'local' ? 0x00ff00 : 0xff0000,
    });
    const mesh = new THREE.Mesh(geometry, material);
    mesh.position.y = 1;
    mesh.castShadow = true;

    const playerGroup = new THREE.Group();
    playerGroup.add(mesh);

    this.scene.add(playerGroup);
    this.players.set(id, playerGroup);

    return playerGroup;
  }

  setCameraTarget(x: number, y: number, z: number): void {
    // Convert game coordinates to Three.js coordinates
    // OSRS uses a different coordinate system
    const worldX = x - 3200; // Offset from Lumbridge area
    const worldZ = y - 3200;
    const worldY = z * 4; // Height level

    this.cameraTarget.set(worldX, worldY, worldZ);

    // Update local player position
    const localPlayer = this.players.get('local');
    if (localPlayer) {
      localPlayer.position.set(worldX, worldY, worldZ);
    }
  }

  updateCamera(): void {
    const distance = 40 / this.cameraZoom;
    const offsetX = Math.sin(this.cameraRotation) * distance;
    const offsetZ = Math.cos(this.cameraRotation) * distance;
    const offsetY = distance * this.cameraPitch;

    this.camera.position.set(
      this.cameraTarget.x + offsetX,
      this.cameraTarget.y + offsetY,
      this.cameraTarget.z + offsetZ
    );
    this.camera.lookAt(this.cameraTarget);
  }

  rotateCamera(delta: number): void {
    this.cameraRotation += delta;
  }

  pitchCamera(delta: number): void {
    this.cameraPitch = Math.max(0.1, Math.min(1.2, this.cameraPitch + delta));
  }

  zoomCamera(delta: number): void {
    this.cameraZoom = Math.max(0.5, Math.min(2, this.cameraZoom + delta));
  }

  render(): void {
    this.updateCamera();
    this.renderer.render(this.scene, this.camera);
  }

  resize(width: number, height: number): void {
    this.camera.aspect = width / height;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(width, height);
  }

  dispose(): void {
    this.renderer.dispose();

    // Dispose all geometries and materials
    this.scene.traverse((object) => {
      if (object instanceof THREE.Mesh) {
        object.geometry.dispose();
        if (object.material instanceof THREE.Material) {
          object.material.dispose();
        } else if (Array.isArray(object.material)) {
          object.material.forEach((m) => m.dispose());
        }
      }
    });
  }

  // Methods for loading actual game assets
  loadTerrain(regionX: number, regionY: number): Promise<void> {
    // Will load terrain from cache
    return Promise.resolve();
  }

  updatePlayer(id: string, x: number, y: number, z: number, animation?: string): void {
    let player = this.players.get(id);
    if (!player) {
      player = this.createPlayerModel(id);
    }

    const worldX = x - 3200;
    const worldZ = y - 3200;
    const worldY = z * 4;

    player.position.set(worldX, worldY, worldZ);
  }

  removePlayer(id: string): void {
    const player = this.players.get(id);
    if (player) {
      this.scene.remove(player);
      this.players.delete(id);
    }
  }

  addGroundItem(id: string, itemId: number, x: number, y: number, z: number): void {
    // Placeholder for ground items
    const geometry = new THREE.BoxGeometry(0.5, 0.5, 0.5);
    const material = new THREE.MeshLambertMaterial({ color: 0xffff00 });
    const mesh = new THREE.Mesh(geometry, material);

    const worldX = x - 3200;
    const worldZ = y - 3200;
    const worldY = z * 4 + 0.25;

    mesh.position.set(worldX, worldY, worldZ);
    this.scene.add(mesh);
    this.groundItems.set(id, mesh);
  }

  removeGroundItem(id: string): void {
    const item = this.groundItems.get(id);
    if (item) {
      this.scene.remove(item);
      this.groundItems.delete(id);
    }
  }
}
