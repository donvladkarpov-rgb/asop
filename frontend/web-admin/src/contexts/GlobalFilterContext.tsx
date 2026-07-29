import { createContext, useContext, useState, type ReactNode } from 'react';

interface GlobalFilterState {
  regionId: string;
  carrierId: string;
  cardsDistributorId: string;
  setRegionId: (id: string) => void;
  setCarrierId: (id: string) => void;
  setCardsDistributorId: (id: string) => void;
}

const GlobalFilterContext = createContext<GlobalFilterState | null>(null);

export function GlobalFilterProvider({ children }: { children: ReactNode }) {
  const [regionId, setRegionId] = useState('');
  const [carrierId, setCarrierId] = useState('');
  const [cardsDistributorId, setCardsDistributorId] = useState('');

  return (
    <GlobalFilterContext.Provider value={{ regionId, carrierId, cardsDistributorId, setRegionId, setCarrierId, setCardsDistributorId }}>
      {children}
    </GlobalFilterContext.Provider>
  );
}

export function useGlobalFilter(): GlobalFilterState {
  const ctx = useContext(GlobalFilterContext);
  if (!ctx) throw new Error('useGlobalFilter must be used within GlobalFilterProvider');
  return ctx;
}
