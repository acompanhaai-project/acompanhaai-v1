import { Routes } from '@angular/router';
import { requireSession, restoreSessionOnLogin } from './core/auth/session-guards';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'entrar' },
  {
    path: 'entrar',
    canActivate: [restoreSessionOnLogin],
    loadComponent: () => import('./auth-page/auth-page.component').then((m) => m.AuthPageComponent),
  },
  {
    path: 'conta',
    canActivate: [requireSession],
    runGuardsAndResolvers: 'always',
    data: { requiresAuth: true },
    loadComponent: () => import('./auth-page/auth-page.component').then((m) => m.AuthPageComponent),
  },
  {
    path: 'conta/perfil',
    canActivate: [requireSession],
    data: { requiresAuth: true },
    loadComponent: () =>
      import('./profile-page/profile-page.component').then((m) => m.ProfilePageComponent),
  },
  {
    path: 'candidaturas/nova',
    canActivate: [requireSession],
    data: { requiresAuth: true },
    loadComponent: () =>
      import('./application-page/application-page.component').then(
        (m) => m.ApplicationPageComponent,
      ),
  },
  {
    path: 'candidaturas/importar',
    canActivate: [requireSession],
    data: { requiresAuth: true },
    loadComponent: () =>
      import('./import-page/import-page.component').then((m) => m.ImportPageComponent),
  },
  {
    path: 'candidaturas/importar/:id',
    canActivate: [requireSession],
    data: { requiresAuth: true },
    loadComponent: () =>
      import('./import-page/import-page.component').then((m) => m.ImportPageComponent),
  },
  {
    path: 'candidaturas/:id',
    canActivate: [requireSession],
    data: { requiresAuth: true },
    loadComponent: () =>
      import('./application-page/application-page.component').then(
        (m) => m.ApplicationPageComponent,
      ),
  },
  {
    path: 'candidaturas',
    canActivate: [requireSession],
    data: { requiresAuth: true },
    loadComponent: () =>
      import('./applications-board/applications-board.component').then(
        (module) => module.ApplicationsBoardComponent,
      ),
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
