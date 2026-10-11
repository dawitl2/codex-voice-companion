import { randomUUID } from 'node:crypto';
import type { BridgeEvent, EventType } from '../types/index.js';
import { sanitize } from '../utils/safe.js';
export class EventNormalizer {
  create(type: EventType, summary: string, scope: { threadId?: string; turnId?: string; metadata?: BridgeEvent['metadata'] } = {}): BridgeEvent {
    const event: BridgeEvent = { id: randomUUID(), timestamp: new Date().toISOString(), type, summary: sanitize(summary) };
    if (scope.threadId) event.threadId = scope.threadId;
    if (scope.turnId) event.turnId = scope.turnId;
    if (scope.metadata) event.metadata = scope.metadata;
    return event;
  }
}
