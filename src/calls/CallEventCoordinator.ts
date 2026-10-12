import type { Bridge } from '../core/Bridge.js';
import type { DeviceRegistry } from '../remote/DeviceRegistry.js';
import { eligible } from '../notifications/NotificationPolicy.js';
import type { CallSessionManager } from './CallSessionManager.js';

/** Shares normalized lifecycle identity with notifications; never creates inference. */
export class CallEventCoordinator {
  private readonly unsubscribe: () => void;
  diagnostic = 'ready';
  constructor(bridge: Bridge, registry: DeviceRegistry, calls: CallSessionManager) {
    this.unsubscribe = bridge.events.subscribe(raw => {
      const event = eligible(raw, bridge.mode, Date.now(), bridge.store.state.approvals); if (!event) return;
      try {
        for (const target of registry.pushTargets()) if (target.preferences.enabled && target.preferences[event.kind] &&
          Date.parse(event.occurredAt) >= Date.parse(target.registeredAt)) calls.offer(target.id, event);
      } catch { this.diagnostic = 'call_storage_unavailable'; }
    });
  }
  close() { this.unsubscribe(); }
}
