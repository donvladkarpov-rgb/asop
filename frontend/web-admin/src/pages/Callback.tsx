import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { userManager } from '../auth/config';

export function CallbackPage() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    userManager
      .signinRedirectCallback()
      .then(() => navigate('/', { replace: true }))
      .catch((err) => {
        setError(err.message);
      });
  }, [navigate]);

  if (error) return <div>Ошибка авторизации: {error}</div>;
  return <div>Выполняется вход...</div>;
}
