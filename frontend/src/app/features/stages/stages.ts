import { Component } from '@angular/core';
import { APPLICATION_STAGES } from '../../shared/models/application-stage';

@Component({
  selector: 'app-stages',
  templateUrl: './stages.html',
  styleUrl: './stages.scss',
})
export class Stages {
  protected readonly groups = [
    {
      label: 'Antes da inscrição',
      stages: APPLICATION_STAGES.filter((stage) => stage.group === 'saved'),
    },
    {
      label: 'Processo em andamento',
      stages: APPLICATION_STAGES.filter((stage) => stage.group === 'active'),
    },
    {
      label: 'Processos finalizados',
      stages: APPLICATION_STAGES.filter((stage) => stage.group === 'closed'),
    },
  ];
}
