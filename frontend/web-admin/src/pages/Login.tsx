import { userManager } from '../auth/config';

export function LoginPage() {
  const handleLogin = () => userManager.signinRedirect();

  return (
    <div className="login-page">
      <div className="login-form">
        <h1>ASOP Admin</h1>
        <p style={{ marginBottom: 24, color: '#6b7280', textAlign: 'center' }}>
          Sign in with your Keycloak account
        </p>
        <button onClick={handleLogin} type="button">Sign In with Keycloak</button>
      </div>
    </div>
  );
}
