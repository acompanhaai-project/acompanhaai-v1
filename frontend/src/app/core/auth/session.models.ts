export interface User {
  readonly id: number;
  readonly name: string;
  readonly email: string;
  readonly createdAt: string;
  readonly photoUrl: string | null;
}

export interface LoginRequest {
  readonly email: string;
  readonly password: string;
}

export interface RegistrationRequest extends LoginRequest {
  readonly name: string;
}

export interface ProfileUpdateRequest {
  readonly name: string;
  readonly email: string;
}

export interface PasswordChangeRequest {
  readonly currentPassword: string;
  readonly newPassword: string;
}

export interface CsrfToken {
  readonly headerName: string;
  readonly token: string;
}

export type SessionStatus = 'unknown' | 'authenticated' | 'anonymous';
