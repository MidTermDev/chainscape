import { getWebSocketClient } from '../../network/WebSocketClient';
import { ClientOpcode } from '../../network/PacketHandler';

export interface InputState {
  mouseX: number;
  mouseY: number;
  mouseDown: boolean;
  rightMouseDown: boolean;
  middleMouseDown: boolean;
  keys: Set<string>;
  touchActive: boolean;
  touchStartX: number;
  touchStartY: number;
}

export interface ClickEvent {
  x: number;
  y: number;
  button: number;
  shift: boolean;
  ctrl: boolean;
}

export class InputHandler {
  private canvas: HTMLCanvasElement;
  private state: InputState;
  private clickListeners: ((event: ClickEvent) => void)[] = [];
  private cameraRotateSpeed = 0.01;
  private cameraPitchSpeed = 0.005;
  private cameraZoomSpeed = 0.1;

  // Camera control state
  private isDragging = false;
  private lastMouseX = 0;
  private lastMouseY = 0;

  constructor(canvas: HTMLCanvasElement) {
    this.canvas = canvas;
    this.state = {
      mouseX: 0,
      mouseY: 0,
      mouseDown: false,
      rightMouseDown: false,
      middleMouseDown: false,
      keys: new Set(),
      touchActive: false,
      touchStartX: 0,
      touchStartY: 0,
    };

    this.bindEvents();
  }

  private bindEvents(): void {
    // Mouse events
    this.canvas.addEventListener('mousedown', this.handleMouseDown.bind(this));
    this.canvas.addEventListener('mouseup', this.handleMouseUp.bind(this));
    this.canvas.addEventListener('mousemove', this.handleMouseMove.bind(this));
    this.canvas.addEventListener('wheel', this.handleWheel.bind(this));
    this.canvas.addEventListener('contextmenu', this.handleContextMenu.bind(this));
    this.canvas.addEventListener('click', this.handleClick.bind(this));

    // Keyboard events
    window.addEventListener('keydown', this.handleKeyDown.bind(this));
    window.addEventListener('keyup', this.handleKeyUp.bind(this));

    // Touch events
    this.canvas.addEventListener('touchstart', this.handleTouchStart.bind(this));
    this.canvas.addEventListener('touchend', this.handleTouchEnd.bind(this));
    this.canvas.addEventListener('touchmove', this.handleTouchMove.bind(this));
  }

  private handleMouseDown(event: MouseEvent): void {
    event.preventDefault();

    this.state.mouseX = event.clientX;
    this.state.mouseY = event.clientY;

    if (event.button === 0) {
      this.state.mouseDown = true;
    } else if (event.button === 2) {
      this.state.rightMouseDown = true;
      this.isDragging = true;
      this.lastMouseX = event.clientX;
      this.lastMouseY = event.clientY;
    } else if (event.button === 1) {
      this.state.middleMouseDown = true;
      this.isDragging = true;
      this.lastMouseX = event.clientX;
      this.lastMouseY = event.clientY;
    }
  }

  private handleMouseUp(event: MouseEvent): void {
    if (event.button === 0) {
      this.state.mouseDown = false;
    } else if (event.button === 2) {
      this.state.rightMouseDown = false;
      this.isDragging = false;
    } else if (event.button === 1) {
      this.state.middleMouseDown = false;
      this.isDragging = false;
    }
  }

  private handleMouseMove(event: MouseEvent): void {
    this.state.mouseX = event.clientX;
    this.state.mouseY = event.clientY;

    if (this.isDragging) {
      const deltaX = event.clientX - this.lastMouseX;
      const deltaY = event.clientY - this.lastMouseY;

      // Emit camera rotation event
      window.dispatchEvent(
        new CustomEvent('input:cameraRotate', {
          detail: {
            yaw: deltaX * this.cameraRotateSpeed,
            pitch: deltaY * this.cameraPitchSpeed,
          },
        })
      );

      this.lastMouseX = event.clientX;
      this.lastMouseY = event.clientY;
    }
  }

  private handleWheel(event: WheelEvent): void {
    event.preventDefault();

    const delta = event.deltaY > 0 ? -this.cameraZoomSpeed : this.cameraZoomSpeed;

    window.dispatchEvent(
      new CustomEvent('input:cameraZoom', {
        detail: { delta },
      })
    );
  }

  private handleContextMenu(event: MouseEvent): void {
    event.preventDefault();
  }

  private handleClick(event: MouseEvent): void {
    if (event.button !== 0) return;

    const clickEvent: ClickEvent = {
      x: event.clientX,
      y: event.clientY,
      button: event.button,
      shift: event.shiftKey,
      ctrl: event.ctrlKey,
    };

    // Notify listeners
    this.clickListeners.forEach((listener) => listener(clickEvent));

    // Convert screen coordinates to world coordinates and send movement
    this.handleGameClick(clickEvent);
  }

  private handleGameClick(event: ClickEvent): void {
    // Ray casting to determine world position
    // This is a simplified version - actual implementation would use Three.js raycaster
    const rect = this.canvas.getBoundingClientRect();
    const x = ((event.x - rect.left) / rect.width) * 2 - 1;
    const y = -((event.y - rect.top) / rect.height) * 2 + 1;

    // Emit click event for game logic
    window.dispatchEvent(
      new CustomEvent('input:gameClick', {
        detail: {
          screenX: event.x,
          screenY: event.y,
          normalizedX: x,
          normalizedY: y,
          shift: event.shift,
          ctrl: event.ctrl,
        },
      })
    );
  }

  private handleKeyDown(event: KeyboardEvent): void {
    // Don't capture input if user is typing in a text field
    if (
      event.target instanceof HTMLInputElement ||
      event.target instanceof HTMLTextAreaElement
    ) {
      return;
    }

    this.state.keys.add(event.code);

    // Handle special keys
    switch (event.code) {
      case 'ArrowLeft':
      case 'KeyA':
        window.dispatchEvent(
          new CustomEvent('input:cameraRotate', {
            detail: { yaw: -0.1, pitch: 0 },
          })
        );
        break;
      case 'ArrowRight':
      case 'KeyD':
        window.dispatchEvent(
          new CustomEvent('input:cameraRotate', {
            detail: { yaw: 0.1, pitch: 0 },
          })
        );
        break;
      case 'ArrowUp':
      case 'KeyW':
        window.dispatchEvent(
          new CustomEvent('input:cameraRotate', {
            detail: { yaw: 0, pitch: -0.05 },
          })
        );
        break;
      case 'ArrowDown':
      case 'KeyS':
        window.dispatchEvent(
          new CustomEvent('input:cameraRotate', {
            detail: { yaw: 0, pitch: 0.05 },
          })
        );
        break;
      case 'Enter':
        window.dispatchEvent(new CustomEvent('input:openChat'));
        break;
      case 'Escape':
        window.dispatchEvent(new CustomEvent('input:closeAll'));
        break;
      case 'F1':
      case 'F2':
      case 'F3':
      case 'F4':
      case 'F5':
        event.preventDefault();
        window.dispatchEvent(
          new CustomEvent('input:functionKey', {
            detail: { key: event.code },
          })
        );
        break;
    }
  }

  private handleKeyUp(event: KeyboardEvent): void {
    this.state.keys.delete(event.code);
  }

  private handleTouchStart(event: TouchEvent): void {
    if (event.touches.length === 1) {
      const touch = event.touches[0];
      this.state.touchActive = true;
      this.state.touchStartX = touch.clientX;
      this.state.touchStartY = touch.clientY;
      this.state.mouseX = touch.clientX;
      this.state.mouseY = touch.clientY;
    } else if (event.touches.length === 2) {
      // Two-finger touch for camera rotation
      this.isDragging = true;
      const centerX = (event.touches[0].clientX + event.touches[1].clientX) / 2;
      const centerY = (event.touches[0].clientY + event.touches[1].clientY) / 2;
      this.lastMouseX = centerX;
      this.lastMouseY = centerY;
    }
  }

  private handleTouchEnd(event: TouchEvent): void {
    if (event.touches.length === 0) {
      this.state.touchActive = false;
      this.isDragging = false;

      // Check if it was a tap (not a drag)
      const deltaX = this.state.mouseX - this.state.touchStartX;
      const deltaY = this.state.mouseY - this.state.touchStartY;

      if (Math.abs(deltaX) < 10 && Math.abs(deltaY) < 10) {
        this.handleGameClick({
          x: this.state.touchStartX,
          y: this.state.touchStartY,
          button: 0,
          shift: false,
          ctrl: false,
        });
      }
    }
  }

  private handleTouchMove(event: TouchEvent): void {
    event.preventDefault();

    if (event.touches.length === 1 && this.state.touchActive) {
      const touch = event.touches[0];
      this.state.mouseX = touch.clientX;
      this.state.mouseY = touch.clientY;
    } else if (event.touches.length === 2 && this.isDragging) {
      const centerX = (event.touches[0].clientX + event.touches[1].clientX) / 2;
      const centerY = (event.touches[0].clientY + event.touches[1].clientY) / 2;

      const deltaX = centerX - this.lastMouseX;
      const deltaY = centerY - this.lastMouseY;

      window.dispatchEvent(
        new CustomEvent('input:cameraRotate', {
          detail: {
            yaw: deltaX * this.cameraRotateSpeed,
            pitch: deltaY * this.cameraPitchSpeed,
          },
        })
      );

      this.lastMouseX = centerX;
      this.lastMouseY = centerY;
    }
  }

  onGameClick(listener: (event: ClickEvent) => void): void {
    this.clickListeners.push(listener);
  }

  isKeyDown(code: string): boolean {
    return this.state.keys.has(code);
  }

  getMousePosition(): { x: number; y: number } {
    return { x: this.state.mouseX, y: this.state.mouseY };
  }

  dispose(): void {
    // Remove all event listeners
    this.canvas.removeEventListener('mousedown', this.handleMouseDown.bind(this));
    this.canvas.removeEventListener('mouseup', this.handleMouseUp.bind(this));
    this.canvas.removeEventListener('mousemove', this.handleMouseMove.bind(this));
    this.canvas.removeEventListener('wheel', this.handleWheel.bind(this));
    this.canvas.removeEventListener('contextmenu', this.handleContextMenu.bind(this));
    this.canvas.removeEventListener('click', this.handleClick.bind(this));
    this.canvas.removeEventListener('touchstart', this.handleTouchStart.bind(this));
    this.canvas.removeEventListener('touchend', this.handleTouchEnd.bind(this));
    this.canvas.removeEventListener('touchmove', this.handleTouchMove.bind(this));
    window.removeEventListener('keydown', this.handleKeyDown.bind(this));
    window.removeEventListener('keyup', this.handleKeyUp.bind(this));
  }
}
