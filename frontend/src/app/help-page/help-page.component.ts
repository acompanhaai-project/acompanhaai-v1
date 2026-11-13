import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApplicationStatus, STATUS_LABELS } from '../applications-board/application.models';
import { SessionState } from '../core/auth/session-state';

const STAGES: readonly { status: ApplicationStatus; meaning: string }[] = [
  { status: 'SAVED', meaning: 'Vaga guardada para decidir depois. Você ainda não se candidatou.' },
  { status: 'APPLIED', meaning: 'Você já enviou a candidatura no site da empresa.' },
  { status: 'SCREENING', meaning: 'A empresa está analisando o seu perfil ou currículo.' },
  { status: 'INTERVIEW', meaning: 'Há entrevista marcada ou em andamento.' },
  { status: 'OFFER', meaning: 'Você recebeu uma proposta.' },
  { status: 'HIRED', meaning: 'Você aceitou a proposta e foi contratado.' },
  { status: 'REJECTED', meaning: 'A empresa encerrou o processo com você.' },
  { status: 'WITHDRAWN', meaning: 'Você decidiu sair do processo.' },
];

@Component({
  selector: 'app-help-page',
  standalone: true,
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './help-page.component.html',
  styleUrl: './help-page.component.scss',
})
export class HelpPageComponent {
  protected readonly authenticated = inject(SessionState).authenticated;
  protected readonly labels = STATUS_LABELS;
  protected readonly stages = STAGES;
}
