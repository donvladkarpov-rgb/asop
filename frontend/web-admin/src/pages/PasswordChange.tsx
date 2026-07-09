import { useState } from 'react';
import { changePassword } from '../api/users';

export function PasswordChangePage() {
  const [current, setCurrent] = useState('');
  const [newPass, setNewPass] = useState('');
  const [confirm, setConfirm] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setMessage('');
    setError('');

    if (!current || !newPass || !confirm) {
      setError('All fields are required');
      return;
    }
    if (newPass.length < 6) {
      setError('New password must be at least 6 characters');
      return;
    }
    if (newPass !== confirm) {
      setError('New passwords do not match');
      return;
    }

    try {
      await changePassword(current, newPass);
      setMessage('Password changed successfully');
      setCurrent('');
      setNewPass('');
      setConfirm('');
    } catch (err: unknown) {
      const detail = err instanceof Error ? err.message : 'Failed to change password';
      setError(detail);
    }
  };

  return (
    <div>
      <div className="page-header">
        <h1>Change Password</h1>
      </div>
      <div className="form-card">
        <form onSubmit={handleSubmit}>
          {message && <div className="form-success">{message}</div>}
          {error && <div className="form-error">{error}</div>}

          <label htmlFor="current">Current Password</label>
          <input
            id="current"
            type="password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            autoComplete="current-password"
          />

          <label htmlFor="newPass">New Password</label>
          <input
            id="newPass"
            type="password"
            value={newPass}
            onChange={(e) => setNewPass(e.target.value)}
            autoComplete="new-password"
          />

          <label htmlFor="confirm">Confirm New Password</label>
          <input
            id="confirm"
            type="password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            autoComplete="new-password"
          />

          <button type="submit" className="btn-primary">Change Password</button>
        </form>
      </div>
    </div>
  );
}
