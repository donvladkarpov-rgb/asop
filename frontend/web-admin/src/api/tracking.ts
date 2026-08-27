import apiClient from './client';

export interface LiveVehicle {
  vehicleId: string;
  vehicleNumber: string;
  vehicleName: string;
  vehicleType: string;
  latitude: number;
  longitude: number;
  snappedLatitude: number | null;
  snappedLongitude: number | null;
  speedKmh: number | null;
  recordedAt: string;
  pathId: string | null;
  pathName: string | null;
  routeId: string | null;
  sessionId: string | null;
}

export async function getLiveVehicles(params: {
  regionId?: string;
  carrierId?: string;
  vehicleId?: string;
  freshSec?: number;
}): Promise<LiveVehicle[]> {
  const { data } = await apiClient.get<LiveVehicle[]>('/tracking/live', { params });
  return data;
}

export async function getVehicleTrack(
  vehicleId: string,
  minutes: number = 15,
): Promise<{ latitude: number; longitude: number; snappedLatitude: number | null; snappedLongitude: number | null; recordedAt: string; speedKmh: number | null }[]> {
  const { data } = await apiClient.get(`/tracking/vehicle/${vehicleId}/track`, {
    params: { minutes },
  });
  return data;
}
