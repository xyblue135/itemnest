export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const response = await fetch(path, { ...options, headers })
  if (!response.ok) {
    let message = `请求失败 ${response.status}`
    try {
      const body = await response.json()
      message = body.detail || body.message || message
    } catch { /* keep HTTP status */ }
    throw new Error(message)
  }
  if (response.status === 204) return undefined as T
  const type = response.headers.get('content-type') || ''
  if (!type.includes('application/json')) return undefined as T
  return response.json() as Promise<T>
}
