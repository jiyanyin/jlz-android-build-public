export type RemoteMessage = {
  id: string; text: string; at: string; fromCompanion: boolean;
  handled?: boolean; reply_to?: string; clientEventId?: string;
};

const str = (value: unknown) => typeof value === "string" ? value : typeof value === "number" ? String(value) : "";
const obj = (value: unknown): Record<string, unknown> => value && typeof value === "object" ? value as Record<string, unknown> : {};

export function extractMessages(payload: unknown): RemoteMessage[] {
  const root = obj(payload);
  const rows = Array.isArray(payload) ? payload : Array.isArray(root.messages) ? root.messages : Array.isArray(root.items) ? root.items : [];
  return rows.map(raw => {
    const m = obj(raw);
    const fromCompanion = !["user", "web", "me", "yanyin", "human"].includes(str(m.role ?? m.sender ?? m.from ?? m.author).toLowerCase());
    const stamp = m.at ?? m.created_at ?? m.timestamp ?? m.created_at_ms;
    const at = typeof stamp === "number" ? stamp * (stamp < 1e12 ? 1000 : 1) : Date.parse(str(stamp));
    return {
      // event_id links a reply to the input which prompted it; it does not own
      // the bubble. Several distinct messages may share that association.
      id: str(m.id || m.event_id), text: str(m.text ?? m.content ?? m.body),
      at: new Date(Number.isFinite(at) ? at : Date.now()).toISOString(), fromCompanion,
      handled: Boolean(m.handled), reply_to: str(m.reply_to ?? m.reply_to_id),
      clientEventId: fromCompanion ? undefined : str(m.client_event_id || m.event_id || m.id),
    };
  }).filter(m => m.id && m.text);
}

/** Both recent and paginated snapshots update the same identities, without deleting visible history. */
export function mergeEchoMessages(...batches: RemoteMessage[][]): RemoteMessage[] {
  const byId = new Map<string, RemoteMessage>();
  for (const batch of batches) for (const message of batch)
    byId.set(`${message.fromCompanion ? "companion" : "user"}:${message.id}`, message);
  return [...byId.values()].sort((a,b) => Date.parse(b.at)-Date.parse(a.at));
}

export function pendingEchoMessages<T extends {event_id?: string}>(local: T[], remote: RemoteMessage[]): T[] {
  const accepted = new Set(remote.filter(m => !m.fromCompanion).flatMap(m => [m.id, m.clientEventId].filter(Boolean)));
  return local.filter(m => !m.event_id || !accepted.has(m.event_id));
}
