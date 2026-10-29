let cachedCsrfToken = null;

async function getCsrfToken(forceRefresh = false) {
    if (cachedCsrfToken && !forceRefresh) return cachedCsrfToken;
    const response = await fetch('/api/auth/csrf', {
        headers: { 'Accept': 'application/json' },
        cache: 'no-store'
    });
    if (!response.ok) throw new Error('无法初始化安全请求，请刷新页面重试');
    cachedCsrfToken = await response.json();
    return cachedCsrfToken;
}

async function apiFetch(url, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = new Headers(options.headers || {});
    headers.set('Accept', 'application/json');

    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
        const csrf = await getCsrfToken();
        headers.set(csrf.headerName || 'X-XSRF-TOKEN', csrf.token);
        if (options.json !== undefined) {
            headers.set('Content-Type', 'application/json');
            options = { ...options, body: JSON.stringify(options.json) };
        }
    }

    const response = await fetch(url, { ...options, method, headers });
    if (response.status === 401 && !url.endsWith('/api/auth/login')) {
        window.location.replace('/login.html');
        throw new Error('登录状态已失效');
    }
    return response;
}

async function readApiError(response) {
    const body = await response.json().catch(() => ({}));
    return body.error || ('请求失败 ' + response.status);
}

async function loadCurrentUser() {
    const response = await apiFetch('/api/auth/me');
    if (!response.ok) throw new Error(await readApiError(response));
    return response.json();
}

async function logout() {
    const response = await apiFetch('/api/auth/logout', { method: 'POST' });
    if (!response.ok && response.status !== 204) {
        throw new Error(await readApiError(response));
    }
    cachedCsrfToken = null;
    window.location.replace('/login.html');
}
