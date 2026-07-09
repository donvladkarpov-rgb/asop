import { NavLink } from 'react-router-dom';
import { cn } from '../lib/utils';
import { useAuth } from '../auth/useAuth';

const navItems = [
  { to: '/', label: 'Dashboard', icon: '📊' },
  { to: '/users', label: 'Users', icon: '👥' },
  { to: '/terminals', label: 'Terminals', icon: '💳' },
  { to: '/cards', label: 'Cards', icon: '🪪' },
  { to: '/carriers', label: 'Carriers', icon: '🚌' },
  { to: '/sessions', label: 'Sessions', icon: '🔐' },
];

const bottomItems = [
  { to: '/password', label: 'Change Password', icon: '🔑' },
];

export function Sidebar() {
  const { logout } = useAuth();

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <h2>ASOP Admin</h2>
      </div>
      <nav className="sidebar-nav">
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/'}
            className={({ isActive }) =>
              cn('nav-link', isActive && 'active')
            }
          >
            <span className="nav-icon">{item.icon}</span>
            <span className="nav-label">{item.label}</span>
          </NavLink>
        ))}
      </nav>
      <div className="sidebar-footer">
        {bottomItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              cn('nav-link', isActive && 'active')
            }
          >
            <span className="nav-icon">{item.icon}</span>
            <span className="nav-label">{item.label}</span>
          </NavLink>
        ))}
        <button className="nav-link logout-btn" onClick={() => logout()}>
          <span className="nav-icon">🚪</span>
          <span className="nav-label">Logout</span>
        </button>
      </div>
    </aside>
  );
}
