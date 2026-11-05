import { Routes } from '@angular/router';
import { isDevMode } from '@angular/core';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'inicio' },
  {
    path: 'inicio',
    title: 'Início | Acompanhaai',
    loadComponent: () => import('./features/home/home').then((module) => module.Home),
  },
  {
    path: 'etapas',
    title: 'Etapas da candidatura | Acompanhaai',
    loadComponent: () => import('./features/stages/stages').then((module) => module.Stages),
  },
  ...(isDevMode()
    ? [
        {
          path: 'referencias',
          title: 'Referências de interface | Acompanhaai',
          loadComponent: () =>
            import('./features/ui-reference/ui-reference').then((module) => module.UiReference),
        },
      ]
    : []),
  {
    path: '**',
    title: 'Página não encontrada | Acompanhaai',
    loadComponent: () => import('./features/not-found/not-found').then((module) => module.NotFound),
  },
];
