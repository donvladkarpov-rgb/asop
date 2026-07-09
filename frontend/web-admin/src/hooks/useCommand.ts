import { useState, useCallback, useRef } from 'react';

export interface AcceptedResponse {
  eventId: string;
  topic: string;
  acceptedAt: string;
  locationHint: string | null;
}

export interface EventStatus {
  eventId: string;
  state: 'PENDING' | 'COMPLETED' | 'FAILED';
  resultData: string | null;
  errorMessage: string | null;
  createdAt: string;
  completedAt: string | null;
}

interface PollResult<T> {
  status: 'pending' | 'completed' | 'failed';
  data: T | null;
  error: string | null;
}

/**
 * Хук для отправки команд через gateway с 202 + polling.
 *
 * Использование:
 *   const { execute, pollResult } = useCommand({
 *     commandFn: () => apiClient.post('/carriers', data),
 *     pollIntervalMs: 2000,
 *     maxPolls: 30,
 *   });
 *
 * При вызове execute() отправляется команда, gateway возвращает
 * 202 + AcceptedResponse. Хук автоматически начинает polling
 * GET /api/v1/events/{eventId} до COMPLETED или FAILED.
 */
export function useCommand<TData = unknown>(
  options: {
    pollIntervalMs?: number;
    maxPolls?: number;
  } = {},
) {
  const { pollIntervalMs = 2000, maxPolls = 30 } = options;
  const [eventId, setEventId] = useState<string | null>(null);
  const [pollResult, setPollResult] = useState<PollResult<TData> | null>(null);
  const [isPolling, setIsPolling] = useState(false);
  const abortRef = useRef(false);

  const pollEvent = useCallback(async (id: string): Promise<PollResult<TData>> => {
    const baseUrl = import.meta.env.VITE_API_URL || '/api/v1';
    const token = sessionStorage.getItem(
      `oidc.user:${import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8180'}/realms/asop:asop-admin`,
    );

    for (let attempt = 0; attempt < maxPolls; attempt++) {
      if (abortRef.current) break;

      await new Promise((r) => setTimeout(r, pollIntervalMs));

      try {
        const headers: Record<string, string> = { 'Content-Type': 'application/json' };
        if (token) {
          const parsed = JSON.parse(token);
          headers.Authorization = `Bearer ${parsed.access_token}`;
        }

        const res = await fetch(`${baseUrl}/events/${id}`, { headers });
        const body = await res.json();

        if (res.status === 200) {
          return { status: 'completed', data: body.resultData as TData, error: null };
        }
        if (res.status === 202) {
          continue; // ещё в обработке
        }
        if (res.status === 422) {
          return { status: 'failed', data: null, error: body.errorMessage || 'Command failed' };
        }
      } catch {
        // игнорируем ошибки polling, повторяем
      }
    }

    return { status: 'failed', data: null, error: 'Polling timeout' };
  }, [pollIntervalMs, maxPolls]);

  const execute = useCallback(
    async (commandFn: () => Promise<AcceptedResponse>) => {
      abortRef.current = false;
      setPollResult(null);

      try {
        const accepted = await commandFn();
        const id = accepted.eventId;
        setEventId(id);

        setIsPolling(true);
        const result = await pollEvent(id);
        setPollResult(result);
        setIsPolling(false);
        return result;
      } catch (err) {
        const result: PollResult<TData> = {
          status: 'failed',
          data: null,
          error: err instanceof Error ? err.message : 'Unknown error',
        };
        setPollResult(result);
        setIsPolling(false);
        return result;
      }
    },
    [pollEvent],
  );

  const cancel = useCallback(() => {
    abortRef.current = true;
    setIsPolling(false);
  }, []);

  return { execute, pollResult, isPolling, eventId, cancel };
}
