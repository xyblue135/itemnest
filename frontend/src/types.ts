export interface Summary {
  containers: number
  items: number
  quantity: number
  special: number
}

export interface Container {
  id: number
  name: string
  notes: string
  item_count: number
  quantity_sum: number
  created_at?: string
  updated_at?: string
}

export interface Item {
  id: number
  container_id: number
  container_name: string
  name: string
  quantity: number | null
  quantity_text: string
  condition: string
  notes: string
  tags: string
  created_at?: string
  updated_at?: string
}

export interface AiSettings {
  base_url: string
  model: string
  has_api_key: boolean
}

export interface MqStatus {
  enabled: boolean
  connected: boolean
  url: string
  exchange: string
  queue: string
  last_error: string
  client: string
}

export interface AiAction {
  type: string
  item_id?: number
  container_id?: number
  data?: Record<string, unknown>
}

export interface ChatResponse {
  reply: string
  action: AiAction | null
  mode: 'ai' | 'local'
}
