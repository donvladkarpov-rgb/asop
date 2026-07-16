import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { userManager } from '../auth/config';

export function CallbackPage() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    userManager
      .signinRedirectCallback()
      .then(() => {
        navigate('/', { replace: true });
      })
      .catch((err) => {
        setError(err.message ?? String(err));
      });
  }, [navigate]);

  if (error) {
    return (
      <div style={{ padding: 20 }}>
        <h2>Ошибка авторизации</h2>
        <p>{error}</p>
        <details style={{ marginTop: 16 }}>
          <summary>URL params</summary>
          <pre>{JSON.stringify(Object.fromEntries(new URLSearchParams(window.location.search)), null, 2)}</pre>
        </details>
        <a href="/login">Попробовать снова</a>
      </div>
    );
  }
  return <div>Обработка входа...</div>;
}
