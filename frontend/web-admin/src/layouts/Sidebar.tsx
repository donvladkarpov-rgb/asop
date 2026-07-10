import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import { cn } from '../lib/utils';
import { useAuth } from '../auth/useAuth';

const navItems = [
  { to: '/', label: 'Панель управления', icon: '📊' },
  { to: '/users', label: 'Пользователи', icon: '👥' },
  { to: '/terminals', label: 'Терминалы', icon: '💳' },
  { to: '/cards', label: 'Карты', icon: '🪪' },
  { to: '/carriers', label: 'Перевозчики', icon: '🚌' },
  { to: '/sessions', label: 'Смены', icon: '🔐' },
];

const regionItems = [
  { to: '/regions', label: 'Регионы', icon: '🗺️' },
  { to: '/territories', label: 'Территории', icon: '📍' },
  { to: '/organizers', label: 'Организаторы', icon: '🏢' },
];

const refItems = [
  { to: '/roles', label: 'Роли', icon: '👤' },
  { to: '/card-types', label: 'Типы карт', icon: '💳' },
  { to: '/tariff-types', label: 'Типы тарифов', icon: '💰' },
  { to: '/session-types', label: 'Типы смен', icon: '🔐' },
  { to: '/event-types', label: 'Типы событий', icon: '📋' },
  { to: '/transaction-types', label: 'Типы транзакций', icon: '🔄' },
  { to: '/transaction-results', label: 'Результаты транзакций', icon: '✅' },
  { to: '/services', label: 'Услуги', icon: '⚙️' },
  { to: '/benefits', label: 'Льготы', icon: '🎫' },
  { to: '/benefit-steps', label: 'Шаги льгот', icon: '📐' }
];

const bottomItems = [
  { to: '/password', label: 'Сменить пароль', icon: '🔑' },
];

function CollapsibleSection({ label, defaultOpen = false, children }: { label: string; defaultOpen?: boolean; children: React.ReactNode }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <>
      <div className="section-toggle" onClick={() => setOpen(!open)}>
        <span className="section-toggle-label">{label}</span>
        <span className={cn('chevron', open && 'chevron-open')}>▶</span>
      </div>
      {open && children}
    </>
  );
}

export function Sidebar() {
  const { logout } = useAuth();

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <h2>ASOP Admin</h2>
      </div>
      <nav className="sidebar-nav sidebar-nav-main">
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
      <CollapsibleSection label="Регионы и территории">
        <nav className="sidebar-nav">
          {regionItems.map((item) => (
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
        </nav>
      </CollapsibleSection>
      <CollapsibleSection label="Справочники">
        <nav className="sidebar-nav">
          {refItems.map((item) => (
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
        </nav>
      </CollapsibleSection>
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
          <span className="nav-label">Выйти</span>
        </button>
      </div>
    </aside>
  );
}
