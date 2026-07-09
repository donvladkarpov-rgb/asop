import { useEffect, useState } from 'react';
import { User } from 'oidc-client-ts';
import { userManager } from './config';

export function useAuth() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    userManager.getUser().then((u) => {
      setUser(u || null);
      setLoading(false);
    });
  }, []);

  const login = () => userManager.signinRedirect();
  const logout = () => userManager.signoutRedirect();
  const getToken = () => user?.access_token ?? null;

  return { user, loading, login, logout, getToken };
}
