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
      setError('Все поля обязательны');
      return;
    }
    if (newPass.length < 6) {
      setError('Новый пароль должен содержать минимум 6 символов');
      return;
    }
    if (newPass !== confirm) {
      setError('Новые пароли не совпадают');
      return;
    }

    try {
      await changePassword(current, newPass);
      setMessage('Пароль успешно изменён');
      setCurrent('');
      setNewPass('');
      setConfirm('');
    } catch (err: unknown) {
      const detail = err instanceof Error ? err.message : 'Не удалось изменить пароль';
      setError(detail);
    }
  };

  return (
    <div>
      <div className="page-header">
        <h1>Смена пароля</h1>
      </div>
      <div className="form-card">
        <form onSubmit={handleSubmit}>
          {message && <div className="form-success">{message}</div>}
          {error && <div className="form-error">{error}</div>}

          <label htmlFor="current">Текущий пароль</label>
          <input
            id="current"
            type="password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            autoComplete="current-password"
          />

          <label htmlFor="newPass">Новый пароль</label>
          <input
            id="newPass"
            type="password"
            value={newPass}
            onChange={(e) => setNewPass(e.target.value)}
            autoComplete="new-password"
          />

          <label htmlFor="confirm">Подтвердите новый пароль</label>
          <input
            id="confirm"
            type="password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            autoComplete="new-password"
          />

          <button type="submit" className="btn-primary">Сменить пароль</button>
        </form>
      </div>
    </div>
  );
}
