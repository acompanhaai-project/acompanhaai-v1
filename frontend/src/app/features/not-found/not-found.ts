import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/ui/empty-state';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink, EmptyState],
  template: `
    <h1>Página não encontrada</h1>
    <section class="panel">
      <app-empty-state
        title="Esse endereço não está disponível"
        message="Confira o endereço ou volte ao início para continuar."
      >
        <a class="button" routerLink="/inicio">Voltar ao início</a>
      </app-empty-state>
    </section>
  `,
})
export class NotFound {}
