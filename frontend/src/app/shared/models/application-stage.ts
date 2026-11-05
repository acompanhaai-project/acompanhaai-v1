export type ApplicationStatus =
  'SAVED' | 'APPLIED' | 'SCREENING' | 'INTERVIEW' | 'OFFER' | 'HIRED' | 'REJECTED' | 'WITHDRAWN';

export interface ApplicationStage {
  readonly status: ApplicationStatus;
  readonly label: string;
  readonly description: string;
  readonly group: 'saved' | 'active' | 'closed';
}

export const APPLICATION_STAGES: readonly ApplicationStage[] = [
  {
    status: 'SAVED',
    label: 'Salva',
    description: 'Uma oportunidade de interesse, antes de enviar a inscrição.',
    group: 'saved',
  },
  {
    status: 'APPLIED',
    label: 'Candidatura realizada',
    description: 'Inscrição enviada. Vale guardar o link e a data para acompanhar.',
    group: 'active',
  },
  {
    status: 'SCREENING',
    label: 'Triagem',
    description: 'A empresa está avaliando seu currículo ou perfil.',
    group: 'active',
  },
  {
    status: 'INTERVIEW',
    label: 'Entrevista',
    description: 'Conversas, avaliações e testes técnicos do processo.',
    group: 'active',
  },
  {
    status: 'OFFER',
    label: 'Proposta',
    description: 'Oferta recebida e condições em análise.',
    group: 'active',
  },
  {
    status: 'HIRED',
    label: 'Contratado',
    description: 'Proposta aceita e contratação confirmada.',
    group: 'closed',
  },
  {
    status: 'REJECTED',
    label: 'Reprovado',
    description: 'A empresa encerrou sua participação neste processo.',
    group: 'closed',
  },
  {
    status: 'WITHDRAWN',
    label: 'Desistência',
    description: 'Você decidiu encerrar sua participação.',
    group: 'closed',
  },
];
