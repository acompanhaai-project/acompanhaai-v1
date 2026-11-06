import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'entrar' },
  {
    path: 'entrar',
    loadComponent: () => import('./auth-page/auth-page.component').then((m) => m.AuthPageComponent),
  },
  {
    path: 'cadastro',
    loadComponent: () =>
      import('./signup-page/signup-page.component').then((m) => m.SignupPageComponent),
  },
  {
    path: 'recuperar-senha',
    loadComponent: () =>
      import('./recuperar-senha-page/recuperar-senha-page.component').then(
        (m) => m.RecuperarSenhaPageComponent,
      ),
  },
  {
    // O token do link de recuperação é recebido pelo fragmento #token=...
    path: 'redefinir-senha',
    loadComponent: () =>
      import('./redefinir-senha-page/redefinir-senha-page.component').then(
        (m) => m.RedefinirSenhaPageComponent,
      ),
  },
  {
    path: 'reset-password',
    loadComponent: () =>
      import('./redefinir-senha-page/redefinir-senha-page.component').then(
        (m) => m.RedefinirSenhaPageComponent,
      ),
  },
  { path: '**', redirectTo: 'entrar' },
];
