import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { GlobalFilterPanel } from './GlobalFilterPanel';
import { GlobalFilterProvider } from '../contexts/GlobalFilterContext';
import './DashboardLayout.css';

export function DashboardLayout() {
  return (
    <GlobalFilterProvider>
      <div className="dashboard-layout">
        <Sidebar />
        <main className="main-content">
          <Outlet />
        </main>
        <GlobalFilterPanel />
      </div>
    </GlobalFilterProvider>
  );
}
