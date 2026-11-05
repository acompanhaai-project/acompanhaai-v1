import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { APPLICATION_STAGES } from '../../shared/models/application-stage';

@Component({
  selector: 'app-home',
  imports: [RouterLink],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home {
  protected readonly activeStages = APPLICATION_STAGES.filter((stage) => stage.group === 'active');
}
