export interface Summary { containers: number; items: number; quantity: number; special: number }
export interface Member { id: number; name: string; sort_order: number }
export interface MemberSummary { id: number; name: string; containers: number; items: number; quantity: number }
export interface LifecycleSummary { expired: number; due7: number; due30: number; total: number }
export interface Dashboard { summary: Summary; members: MemberSummary[]; lifecycle: LifecycleSummary; recent_history: HistoryEntry[] }

export interface Container {
  id: number; name: string; notes: string; owner_id: number; owner_name: string;
  item_count: number; quantity_sum: number; created_at?: string; updated_at?: string
}

export interface Item {
  id: number; container_id: number; container_name: string; owner_id: number; owner_name: string;
  name: string; quantity: number | null; quantity_text: string; condition: string; notes: string; tags: string;
  lifecycle_type?: string | null; lifecycle_start_date?: string | null; expiry_date?: string | null;
  remind_days?: number | null; lifecycle_notes?: string | null; attachment_count?: number;
  created_at?: string; updated_at?: string
}

export interface LifecycleEntry {
  id: number; item_id: number; item_name: string; lifecycle_type: string; start_date?: string | null;
  expiry_date?: string | null; remind_days: number; notes: string; container_id: number; container_name: string;
  owner_id: number; owner_name: string; days_left?: number | null; lifecycle_status: 'EXPIRED'|'DUE'|'ACTIVE'|'NO_DATE'
}

export interface Attachment {
  id: number; item_id: number; kind: 'image'|'file'; filename: string; mime_type: string; size_bytes: number; created_at: string
}

export interface HistoryEntry {
  id: number; action_type: string; entity_type: string; entity_id?: number | null; item_id?: number | null;
  container_id?: number | null; related_container_id?: number | null; owner_id?: number | null;
  source: 'manual'|'ai'|'batch'|'system'|string; description: string; created_at: string; undone_at?: string | null; can_undo?: boolean
}

export interface AiSettings { base_url: string; model: string; has_api_key: boolean }
export interface MqStatus { enabled: boolean; connected: boolean; url: string; exchange: string; queue: string; last_error: string; client: string }
export interface AiAction { type: string; item_id?: number; container_id?: number; data?: Record<string, unknown> }
export interface ChatResponse { reply: string; action: AiAction | null; mode: 'ai' | 'local' }
