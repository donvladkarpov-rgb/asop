import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DashboardLayout } from './layouts/DashboardLayout';
import { DashboardPage } from './pages/Dashboard';
import { UsersPage } from './pages/Users';
import { TerminalsPage } from './pages/Terminals';
import { CardsPage } from './pages/Cards';
import { RegionsPage } from './pages/Regions';
import { TerritoriesPage } from './pages/Territories';
import { OrganizersPage } from './pages/Organizers';
import { RolesPage } from './pages/Roles';
import { CardTypesPage } from './pages/CardTypes';
import { TariffTypesPage } from './pages/TariffTypes';
import { SessionTypesPage } from './pages/SessionTypes';
import { EventTypesPage } from './pages/EventTypes';
import { TransactionTypesPage } from './pages/TransactionTypes';
import { TransactionResultsPage } from './pages/TransactionResults';
import { ServicesPage } from './pages/Services';
import { BenefitsPage } from './pages/Benefits';
import { BenefitStepsPage } from './pages/BenefitSteps';
import { LoginPage } from './pages/Login';
import { CallbackPage } from './pages/Callback';
import { PasswordChangePage } from './pages/PasswordChange';
import { useAuth } from './auth/useAuth';
import { getAccessToken } from './auth/config';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
});

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { loading, user } = useAuth();

  if (loading) return <div>Загрузка...</div>;
  if (!user) return <Navigate to="/login" replace />;

  return <>{children}</>;
}

function AppRoutes() {
  const { loading } = useAuth();

  if (loading) return <div>Загрузка...</div>;

  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/callback" element={<CallbackPage />} />
      <Route
        element={
          <ProtectedRoute>
            <DashboardLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="terminals" element={<TerminalsPage />} />
        <Route path="cards" element={<CardsPage />} />
        <Route path="regions" element={<RegionsPage />} />
        <Route path="territories" element={<TerritoriesPage />} />
        <Route path="organizers" element={<OrganizersPage />} />
        <Route path="roles" element={<RolesPage />} />
        <Route path="card-types" element={<CardTypesPage />} />
        <Route path="tariff-types" element={<TariffTypesPage />} />
        <Route path="session-types" element={<SessionTypesPage />} />
        <Route path="event-types" element={<EventTypesPage />} />
        <Route path="transaction-types" element={<TransactionTypesPage />} />
        <Route path="transaction-results" element={<TransactionResultsPage />} />
        <Route path="services" element={<ServicesPage />} />
        <Route path="benefits" element={<BenefitsPage />} />
        <Route path="benefit-steps" element={<BenefitStepsPage />} />
        <Route path="password" element={<PasswordChangePage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function App() {
  const token = getAccessToken();
  if (token) {
    queryClient.setDefaultOptions({
      queries: {
        staleTime: 30_000,
        retry: 1,
      },
    });
  }

  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </QueryClientProvider>
  );
}

export default App;
