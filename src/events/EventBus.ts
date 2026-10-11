import type { BridgeEvent } from '../types/index.js';
import { errorText } from '../utils/safe.js';
export class EventBus {
  private readonly listeners = new Set<(event: BridgeEvent) => void>();
  constructor(private readonly onListenerError: (message: string) => void = () => {}) {}
  subscribe(listener: (event: BridgeEvent) => void): () => void {
    this.listeners.add(listener); return () => this.listeners.delete(listener);
  }
  emit(event: BridgeEvent): void {
    for (const listener of this.listeners) { try { listener(event); } catch (error) { this.onListenerError(errorText(error)); } }
  }
}
