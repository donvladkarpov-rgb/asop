import apiClient from './client';

export interface TripRegistrationReportRow {
  organizerId: string | null;
  organizerName: string | null;
  carrierId: string | null;
  carrierName: string | null;
  routeId: string | null;
  routeNumber: string | null;
  routeName: string | null;
  shiftId: string | null;
  tripId: string;
  tripSessionTypeId: string | null;
  vehicleTypeName: string | null;
  vehicleModelName: string | null;
  vehicleNumber: string | null;
  vehicleName: string | null;
  shiftStartedAt: string | null;
  terminalNumber: string | null;
  terminalSerial: string | null;
  driverLastName: string | null;
  driverFirstName: string | null;
  driverLastNameInitial: string | null;
  driverPatronymicInitial: string | null;
  driverBirthDate: string | null;
  driverName: string;
  cardNumber: string | null;
  transactionId: string;
  tripAt: string;
  processedAt: string;
  status: string | null;
  passengerCategory: string | null;
  paymentForm: string | null;
  serviceName: string | null;
  tariffTypeName: string | null;
  appliedTariff: string | null;
  regulatedTariff: string | null;
  benefitShare: string | null;
  benefitTripsAfter: number | null;
  amount: string;
  originStopName: string | null;
  destinationStopName: string | null;
  fiscalStatus: string | null;
  fiscalCreatedAt: string | null;
  fiscalConfirmedAt: string | null;
  rrn: string | null;
}

export interface TripRegistrationReportTotal {
  level: number;
  levelName: string;
  organizerId: string | null;
  organizerName: string | null;
  carrierId: string | null;
  carrierName: string | null;
  routeId: string | null;
  routeLabel: string | null;
  shiftId: string | null;
  tripId: string | null;
  tripsCount: number;
  amount: string;
}

export interface TripRegistrationReport {
  title: string;
  dateFrom: string;
  dateTo: string;
  totalRows: number;
  limit: number;
  offset: number;
  totals: TripRegistrationReportTotal[];
  grandTotalAmount: string;
  grandTotalTrips: number;
  rows: TripRegistrationReportRow[];
}

export interface TripRegistrationReportParams {
  dateFrom: string;
  dateTo: string;
  regionId?: string;
  organizerId?: string;
  carrierId?: string;
  routeId?: string;
  pathId?: string;
  vehicleId?: string;
  terminalId?: string;
  driverId?: string;
  transactionTypeIds?: string;
  limit?: number;
  offset?: number;
}

export async function getTripRegistrationReport(
  params: TripRegistrationReportParams,
): Promise<TripRegistrationReport> {
  const { data } = await apiClient.get<TripRegistrationReport>(
    '/reports/trip-registrations',
    { params },
  );
  return data;
}