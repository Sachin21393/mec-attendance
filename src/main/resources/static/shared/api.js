function readCookie(name) {
  const prefix = `${encodeURIComponent(name)}=`;
  const parts = document.cookie.split(';');
  for (const part of parts) {
    const trimmed = part.trim();
    if (trimmed.startsWith(prefix)) {
      return decodeURIComponent(trimmed.substring(prefix.length));
    }
  }
  return null;
}

export async function ensureCsrfToken() {
  let token = readCookie('XSRF-TOKEN');
  if (token) {
    return token;
  }
  const response = await fetch('/api/v1/auth/csrf', {
    method: 'GET',
    credentials: 'same-origin'
  });
  if (response.ok) {
    const data = await response.json();
    return data.token || readCookie('XSRF-TOKEN');
  }
  return null;
}

export class ApiError extends Error {
  constructor(status, code, message, fieldErrors = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}

export async function apiFetch(url, options = {}) {
  if (!navigator.onLine) {
    throw new ApiError(0, 'OFFLINE', "You're offline. Your action has not been completed.");
  }

  const method = (options.method || 'GET').toUpperCase();
  const headers = new Headers(options.headers || {});
  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }

  const isMutating = !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method);
  if (isMutating) {
    const csrf = await ensureCsrfToken();
    if (csrf) {
      headers.set('X-XSRF-TOKEN', csrf);
    }
  }

  let body = options.body;
  if (body && !(body instanceof FormData) && typeof body !== 'string') {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(body);
  }

  let response;
  try {
    response = await fetch(url, {
      ...options,
      method,
      headers,
      body,
      credentials: 'same-origin'
    });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', 'Network error. Please check your connection and try again.');
  }

  const contentType = response.headers.get('content-type') || '';
  const isJson = contentType.includes('application/json');

  if (!response.ok) {
    if (isJson) {
      const errBody = await response.json().catch(() => ({}));
      throw new ApiError(
        response.status,
        errBody.code || 'REQUEST_FAILED',
        errBody.message || `Request failed (${response.status})`,
        errBody.fieldErrors || {}
      );
    }
    throw new ApiError(response.status, 'REQUEST_FAILED', `Request failed (${response.status})`);
  }

  if (response.status === 204) {
    return null;
  }
  return isJson ? response.json() : response.text();
}
