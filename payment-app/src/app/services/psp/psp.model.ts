export interface TransferRequest {
  amount: number;
  receiverPixKey: string;
}

export interface PixKeySearchResult {
  name: string;
  taxId: string;
  institution: string;
}
