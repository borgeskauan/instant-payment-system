export interface TransferPreviewDetails {
  receiver: Party;
}

export interface TransferExecutionRequest {
  senderCustomerId: string;
  receiverPixKey: string;
  amount: number;
  description?: string;
}

export interface TransferDetails {
  transferId: string;
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

export interface Party {
  name: string;
  taxId: string;
  account: BankAccount;
  pixKey: string;
}

export interface BankAccount {
  id: BankAccountId;
  type: string;
}

export interface BankAccountId {
  accountNumber: string;
  agencyNumber: string;
  bankCode: string; // ISPB
}
