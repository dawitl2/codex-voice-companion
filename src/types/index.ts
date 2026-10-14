export type Mode = 'mock' | 'real';
export type TurnStatus = 'starting' | 'running' | 'awaitingApproval' | 'completed' | 'failed' | 'interrupted' | 'unknown';
export type EventType = 'bridge.connected' | 'bridge.disconnected' | 'thread.created' | 'thread.resumed' | 'turn.started' | 'turn.progress' | 'turn.completed' | 'turn.failed' | 'turn.interrupted' | 'approval.requested' | 'approval.resolved' | 'codex.error';
export interface BridgeEvent {
  id: string; timestamp: string; type: EventType; summary: string;
  threadId?: string; turnId?: string; metadata?: Record<string, string | number | boolean>;
}
export interface ManagedThread {
  observed?: boolean;
  sandbox?: 'read-only'|'workspace-write'; original?: boolean; source?: string; rolloutPath?: string; updatedAt?: string; lastSelectedAt?: string;
  id: string; cwd: string; label: string; createdAt: string; model: string;
  status: 'ready' | 'unavailable'; mode: Mode;
}
export interface ManagedTurn {
  instruction?: string;
  id: string; threadId: string; startedAt: string; finishedAt?: string;
  status: TurnStatus; finalResponse: string; error?: string;
  commandResults: { itemId: string; status: string; exitCode: number | null }[];
  completionEmitted: boolean;
}
export interface ApprovalRecord {
  id: string; requestId: string | number; method: string; threadId: string; turnId: string;
  summary: string; requestedAt: string; expiresAt: string;
  status: 'pending' | 'accepted' | 'declined' | 'expired' | 'unavailable' | 'resolved';
}
export interface StoredState { version: 1; mode: Mode; threads: ManagedThread[]; turns: ManagedTurn[]; events: BridgeEvent[]; approvals: ApprovalRecord[] }
export interface RpcCall { id?: string | number; method: string; params?: unknown }
export interface RpcTransport {
  start(): Promise<void>; write(data: string): void; stop(): Promise<void>;
  onData(listener: (data: string) => void): void;
  onClose(listener: (reason: string) => void): void;
  onDiagnostic(listener: (text: string) => void): void;
}
