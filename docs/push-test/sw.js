'use strict';

self.addEventListener('push', (event) => {
  const body = event.data ? event.data.text() : '(no payload)';
  event.waitUntil(
    self.registration.showNotification('プッシュ受信', { body: body })
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil((async () => {
    const windowClients = await self.clients.matchAll({
      type: 'window',
      includeUncontrolled: true
    });
    for (const client of windowClients) {
      if (client.url.startsWith(self.registration.scope) && 'focus' in client) {
        return client.focus();
      }
    }
    if (self.clients.openWindow) {
      return self.clients.openWindow(self.registration.scope);
    }
  })());
});
