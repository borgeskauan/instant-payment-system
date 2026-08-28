export interface TransferPreviewDetails {
  receiver: Party;
}

export interface TransferExecutionRequest {
  senderCustomerId: string;
  receiverPixKey: string;
  amount: number;
  description?: string;
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
