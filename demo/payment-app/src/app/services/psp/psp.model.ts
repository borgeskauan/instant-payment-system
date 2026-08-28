export interface TransferRequest {
  amount: number;
  receiverPixKey: string;
  description?: string;
}

export interface TransferResult {
  transferId: string;
}

export interface PixKeySearchResult {
  name: string;
  taxId: string;
  institution: string;
  bankCode: string;
}

export type PaymentDirection = 'OUTGOING' | 'INCOMING';
export type PaymentStatus = 'PROCESSING' | 'SETTLED' | 'REJECTED';

export interface PaymentSummary {
  paymentId: string;
  direction: PaymentDirection;
  counterparty: {
    name: string;
    pixKey: string;
  };
  amount: number;
  currency: string;
  status: PaymentStatus;
  createdAt: string;
}
