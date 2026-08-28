export interface TransferRequest {
  amount: number;
  receiverPixKey: string;
  description?: string;
}

export interface PixKeySearchResult {
  name: string;
  taxId: string;
  institution: string;
}
