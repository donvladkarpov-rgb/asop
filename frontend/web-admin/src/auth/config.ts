import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

const keycloakUrl = import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8180';
const realm = 'asop';
const clientId = 'asop-admin';

export const userManager = new UserManager({
  authority: `${keycloakUrl}/realms/${realm}`,
  client_id: clientId,
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: `${window.location.origin}/login`,
  response_type: 'code',
  scope: 'openid profile email',
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  automaticSilentRenew: true,
  loadUserInfo: true,
});

export const getAccessToken = (): string | null => {
  const oidcStorage = sessionStorage.getItem(
    `oidc.user:${keycloakUrl}/realms/${realm}:${clientId}`
  );
  if (oidcStorage) {
    return JSON.parse(oidcStorage).access_token;
  }
  return null;
};
