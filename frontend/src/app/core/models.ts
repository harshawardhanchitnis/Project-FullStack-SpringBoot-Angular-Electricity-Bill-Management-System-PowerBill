export type Role = 'CUSTOMER' | 'BILLING_OFFICER' | 'SUPPORT_AGENT' | 'FIELD_ENGINEER' | 'SUPER_ADMIN';
export type Theme = 'SYSTEM' | 'LIGHT' | 'DARK';

export interface Profile {
  id: string;
  email: string;
  fullName: string;
  mobileNumber: string;
  role: Role;
  emailVerified: boolean;
  themePreference: Theme;
  budgetAlerts: boolean;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: Profile;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
}

export interface Account {
  id: string;
  userId: string;
  consumerNumber: string;
  fullName: string;
  accountType: string;
  billingEmail: string;
  mobileNumber: string;
  status: string;
  autoPayEnabled: boolean;
  monthlyBudgetInr: number | null;
  createdAt: string;
  version: number;
}

export interface Premise {
  id: string;
  label: string;
  addressLine: string;
  city: string;
  state: string;
  pincode: string;
  regionZone: string;
}

export interface Meter {
  id: string;
  premiseId: string;
  meterSerial: string;
  connectionType: string;
  sanctionedLoadKw: number;
  solarNetMetering: boolean;
  solarCapacityKw: number | null;
  status: string;
  installedAt: string;
  version: number;
}

export interface ConsumerDetails {
  account: Account;
  premises: Premise[];
  meters: Meter[];
}

export interface BillSummary {
  id: string;
  billNumber: string;
  consumerAccountId: string;
  meterId: string;
  meterSerial: string | null;
  period: string;
  periodStart: string;
  periodEnd: string;
  importKwh: number;
  exportKwh: number;
  netBillableKwh: number;
  totalAmount: number;
  paidAmount: number;
  lateFeePaid: number;
  principalDue: number;
  accruedLateFee: number;
  totalDue: number;
  issueDate: string;
  dueDate: string;
  status: string;
  paidAt: string | null;
}

export interface LineItem {
  lineOrder: number;
  category: string;
  description: string;
  units: number | null;
  rate: number | null;
  amount: number;
}

export interface Note {
  id: string;
  noteNumber: string;
  noteType: 'CREDIT' | 'DEBIT';
  amount: number;
  reason: string;
  createdAt: string;
}

export interface BillDetail {
  bill: BillSummary;
  tariffCode: string;
  tariffVersion: number;
  tariffName: string;
  openingCreditKwh: number;
  carriedForwardCreditKwh: number;
  energyCharge: number;
  todAdjustment: number;
  fixedCharge: number;
  facCharge: number;
  solarExportCredit: number;
  taxAmount: number;
  creditNoteTotal: number;
  debitNoteTotal: number;
  intervalCount: number;
  lineItems: LineItem[];
  notes: Note[];
  consumerNumber: string;
  consumerName: string;
  premiseLabel: string;
}

export interface Receivables {
  openBills: number;
  overdueBills: number;
  principalDue: number;
  lateFeesDue: number;
  nextDueDate: string | null;
}

export interface Allocation {
  billId: string;
  billNumber: string;
  principalAmount: number;
  lateFeeAmount: number;
}

export interface Payment {
  id: string;
  paymentReference: string;
  status: string;
  amount: number;
  currency: string;
  method?: string;
  paymentMethod?: string;
  gatewayProvider: string;
  gatewayOrderId: string;
  gatewayPaymentId: string | null;
  webhookVerified: boolean;
  failureReason: string | null;
  createdAt: string;
  expiresAt: string;
  completedAt: string | null;
  allocations?: Allocation[];
}

export interface LedgerLine {
  id: number;
  createdAt: string;
  referenceType: string;
  referenceId: string;
  description: string;
  entryType: 'DEBIT' | 'CREDIT';
  amount: number;
  runningBalance: number;
}

export interface Band {
  p10: number;
  p50: number;
  p90: number;
}

export interface Forecast {
  meterId: string;
  meterSerial: string;
  cycle: string;
  asOf: string;
  daysElapsed: number;
  daysInCycle: number;
  importKwhToDate: number;
  exportKwhToDate: number;
  projectedKwh: Band;
  projectedBillInr: Band;
  lastCycleKwh: number;
  nextSlabAtKwh: number | null;
  daysToNextSlab: number | null;
  trajectory: { day: string; p10: number; p50: number; p90: number }[];
  model: string;
  source: 'ml' | 'fallback';
  trendPct: number | null;
}

export interface SlabEdge {
  fromKwh: number;
  untilKwh: number | null;
  ratePerKwh: number;
}

export interface Outage {
  id: string;
  incidentCode: string;
  title: string;
  description: string;
  severity: string;
  status: string;
  regionZone: string;
  city: string;
  affectedPincodes: string[];
  startsAt: string;
  estimatedRestorationAt: string | null;
  resolvedAt?: string | null;
  version?: number;
}

export interface ConsumerOverview {
  meter: {
    id: string;
    serial: string;
    connectionType: string;
    sanctionedLoadKw: number;
    solarNetMetering: boolean;
    solarCapacityKw: number | null;
    premiseLabel: string;
    tariffName: string;
  };
  cycle: {
    month: string;
    daysElapsed: number;
    daysInCycle: number;
    importKwhToDate: number;
    exportKwhToDate: number;
    previousCycleSameDaysKwh: number;
    changePct: number | null;
  };
  slab: {
    index: number;
    fromKwh: number;
    untilKwh: number | null;
    ratePerKwh: number;
    nextRatePerKwh: number | null;
    kwhToNextSlab: number | null;
    currentKwh: number;
    slabs: SlabEdge[];
  };
  receivables: Receivables;
  openComplaints: number;
  outages: Outage[];
  monthly: { month: string; importKwh: number; exportKwh: number; billedInr: number | null }[];
  daily: { day: string; importKwh: number; exportKwh: number }[];
  hourlyProfile: { hour: number; importKwh: number; exportKwh: number; window: string }[];
  todShareKwh: Record<string, number>;
  monthlyBudgetInr: number | null;
  openAnomalies: number;
}

export interface Notification {
  id: string;
  category: string;
  title: string;
  message: string;
  link: string | null;
  isRead: boolean;
  createdAt: string;
}

export interface Complaint {
  id: string;
  ticketNumber: string;
  consumerAccountId: string;
  meterId: string | null;
  billId: string | null;
  complaintType: string;
  priority: string;
  status: string;
  subject: string;
  description: string;
  contactNumber: string;
  assignedAgentId: string | null;
  slaDueAt: string;
  slaBreached: boolean;
  resolutionSummary: string | null;
  resolvedAt: string | null;
  csatRating: number | null;
  csatFeedback: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface ComplaintDetail {
  complaint: Complaint;
  comments: {
    id: string;
    authorRole: string;
    authorName: string;
    isInternal: boolean;
    body: string;
    createdAt: string;
  }[];
  history: { fromStatus: string | null; toStatus: string; changedAt: string }[];
}

export interface CopilotAnswer {
  answer: string;
  intent: string;
  citations: { id: string; title: string }[];
  suggestions: string[];
  providerUsed: string;
  grounded: boolean;
}

export interface TariffPlan {
  id: string;
  code: string;
  version: number;
  name: string;
  connectionType: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  fixedChargePerKw: number;
  facPerKwh: number;
  electricityDutyPct: number;
  gstPct: number;
  solarExportRatePerKwh: number;
  solarPolicy: string;
  lateFeeDailyPct: number;
  lateFeeCapPct: number;
  graceDays: number;
  active: boolean;
  slabs: SlabEdge[];
  windows: { name: string; startHour: number; endHour: number; rateMultiplier: number }[];
}

export interface Quote {
  importedKwh: number;
  exportedKwh: number;
  netBillableKwh: number;
  carryForwardKwh: number;
  balancedCreditInr: number;
  totalInr: number;
  lineItems: { category: string; description: string; units: number | null; rate: number | null; amountInr: number }[];
}

export interface Simulation {
  month: string;
  tariffPlanId: string;
  intervalCount: number;
  baseline: Quote;
  projected: Quote;
  annualizedSavingsInr: number;
  solarPaybackYears: number | null;
}

export interface Anomaly {
  id: string;
  meterId: string;
  meterSerial: string;
  windowStart: string;
  windowEnd: string;
  anomalyType: string;
  severity: string;
  score: number;
  status: string;
  resolutionNotes: string | null;
  reasonCodes: string;
  detectedAt: string;
}

export interface StaffMember {
  id: string;
  email: string;
  fullName: string;
  role: Role;
  status: string;
}

export const STAFF_ROLES: Role[] = ['BILLING_OFFICER', 'SUPPORT_AGENT', 'FIELD_ENGINEER', 'SUPER_ADMIN'];
