import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DashboardLayout } from './layouts/DashboardLayout';
import { DashboardPage } from './pages/Dashboard';
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
import { CarriersPage } from './pages/Carriers';
import { TidsPage } from './pages/Tids';
import { CardsDistributorsPage } from './pages/CardsDistributors';
import { ContractsPage } from './pages/Contracts';
import { FareZonesPage } from './pages/routes/FareZonesPage';
import { TransportStopsPage } from './pages/routes/TransportStopsPage';
import { RoutesPage } from './pages/routes/RoutesPage';
import { PathsPage } from './pages/routes/PathsPage';
import { PathTransportStopsPage } from './pages/routes/PathTransportStopsPage';
import { SchedulePage } from './pages/routes/SchedulePage';
import { PathServicesPage } from './pages/routes/PathServicesPage';
import { PathDiscountsPage } from './pages/routes/PathDiscountsPage';
import { PathBenefitsPage } from './pages/routes/PathBenefitsPage';
import { VehiclesPage } from './pages/routes/VehiclesPage';
import { VehicleTypesPage } from './pages/routes/VehicleTypesPage';
import { VehicleModelsPage } from './pages/routes/VehicleModelsPage';
import { ContractRoutesPage } from './pages/routes/ContractRoutesPage';
import { UsersAdminPage } from './pages/security/UsersAdminPage';
import { UserRolesPage } from './pages/security/UserRolesPage';
import { UserCarriersPage } from './pages/security/UserCarriersPage';
import { UserDistributorsPage } from './pages/security/UserDistributorsPage';
import { UserKrsPage } from './pages/security/UserKrsPage';
import { UserRegionsPage } from './pages/security/UserRegionsPage';
import { UserBenefitsPage } from './pages/security/UserBenefitsPage';
import { LoginPage } from './pages/Login';
import { CallbackPage } from './pages/Callback';
import { PasswordChangePage } from './pages/PasswordChange';
import { AsopKeysPage } from './pages/AsopKeys';
import { ConfigParamsPage } from './pages/ConfigParams';
import { SessionsPage } from './pages/Sessions';
import { LiveMapPage } from './pages/LiveMapPage';
import { RouteEditorPage } from './pages/routes/RouteEditorPage';
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
        <Route path="carriers" element={<CarriersPage />} />
        <Route path="tids" element={<TidsPage />} />
        <Route path="cards-distributors" element={<CardsDistributorsPage />} />
        <Route path="contracts" element={<ContractsPage />} />
        <Route path="fare-zones" element={<FareZonesPage />} />
        <Route path="transport-stops" element={<TransportStopsPage />} />
        <Route path="routes" element={<RoutesPage />} />
        <Route path="paths" element={<PathsPage />} />
        <Route path="path-transport-stops" element={<PathTransportStopsPage />} />
        <Route path="schedule" element={<SchedulePage />} />
        <Route path="path-services" element={<PathServicesPage />} />
        <Route path="path-discounts" element={<PathDiscountsPage />} />
        <Route path="path-benefits" element={<PathBenefitsPage />} />
        <Route path="vehicles" element={<VehiclesPage />} />
        <Route path="vehicle-types" element={<VehicleTypesPage />} />
        <Route path="vehicle-models" element={<VehicleModelsPage />} />
        <Route path="contract-routes" element={<ContractRoutesPage />} />
        <Route path="admin-users" element={<UsersAdminPage />} />
        <Route path="user-roles" element={<UserRolesPage />} />
        <Route path="user-carriers" element={<UserCarriersPage />} />
        <Route path="user-distributors" element={<UserDistributorsPage />} />
        <Route path="user-krs" element={<UserKrsPage />} />
        <Route path="user-regions" element={<UserRegionsPage />} />
        <Route path="user-benefits" element={<UserBenefitsPage />} />
        <Route path="asop-keys" element={<AsopKeysPage />} />
        <Route path="config-params" element={<ConfigParamsPage />} />
        <Route path="sessions" element={<SessionsPage />} />
        <Route path="live-map" element={<LiveMapPage />} />
        <Route path="route-editor" element={<RouteEditorPage />} />
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
