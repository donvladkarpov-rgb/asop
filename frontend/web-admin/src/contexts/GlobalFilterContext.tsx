import { createContext, useContext, useState, useEffect, type ReactNode } from 'react';

interface GlobalFilterState {
  regionId: string;
  carrierId: string;
  cardsDistributorId: string;
  setRegionId: (id: string) => void;
  setCarrierId: (id: string) => void;
  setCardsDistributorId: (id: string) => void;
}

const GlobalFilterContext = createContext<GlobalFilterState | null>(null);

const STORAGE_KEY = 'asop.globalFilter';

interface PersistedFilter {
  regionId: string;
  carrierId: string;
  cardsDistributorId: string;
}

function loadPersisted(): PersistedFilter {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<PersistedFilter>;
      return {
        regionId: typeof parsed.regionId === 'string' ? parsed.regionId : '',
        carrierId: typeof parsed.carrierId === 'string' ? parsed.carrierId : '',
        cardsDistributorId: typeof parsed.cardsDistributorId === 'string' ? parsed.cardsDistributorId : '',
      };
    }
  } catch {
    // повреждённый localStorage — игнорируем, стартуем с пустого фильтра
  }
  return { regionId: '', carrierId: '', cardsDistributorId: '' };
}

export function GlobalFilterProvider({ children }: { children: ReactNode }) {
  const [filter, setFilter] = useState<PersistedFilter>(loadPersisted);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(filter));
    } catch {
      // localStorage недоступен (private mode) — фильтр живёт до перезагрузки
    }
  }, [filter]);

  const setRegionId = (id: string) =>
    setFilter((f) => ({ ...f, regionId: id, carrierId: '' })); // регион сбрасывает перевозчика
  const setCarrierId = (id: string) => setFilter((f) => ({ ...f, carrierId: id }));
  const setCardsDistributorId = (id: string) => setFilter((f) => ({ ...f, cardsDistributorId: id }));

  return (
    <GlobalFilterContext.Provider
      value={{
        regionId: filter.regionId,
        carrierId: filter.carrierId,
        cardsDistributorId: filter.cardsDistributorId,
        setRegionId,
        setCarrierId,
        setCardsDistributorId,
      }}
    >
      {children}
    </GlobalFilterContext.Provider>
  );
}

export function useGlobalFilter(): GlobalFilterState {
  const ctx = useContext(GlobalFilterContext);
  if (!ctx) throw new Error('useGlobalFilter must be used within GlobalFilterProvider');
  return ctx;
}
