---
name: Acompanhaai
description: Interface clara para acompanhar candidaturas e seus próximos passos.
colors:
  primary: '#164ec7'
  primary-hover: '#103da0'
  primary-soft: '#eaf0fc'
  background: '#f3f6fb'
  surface: '#ffffff'
  ink: '#0e2346'
  muted: '#486077'
  border: '#d9e2ef'
  field-border: '#74869e'
  danger: '#a52b36'
  success: '#176044'
typography:
  display:
    fontFamily: 'Manrope, sans-serif'
    fontSize: 'clamp(2rem, 4.8vw, 3.75rem)'
    fontWeight: 800
    lineHeight: 1.2
    letterSpacing: '-0.035em'
  body:
    fontFamily: 'Manrope, sans-serif'
    fontSize: '1rem'
    fontWeight: 400
    lineHeight: 1.6
rounded:
  control: '8px'
  panel: '16px'
spacing:
  small: '8px'
  base: '16px'
  section: '32px'
  page: '64px'
---

## Overview

Base visual da KAN-38. Ambiente claro para leitura recorrente das etapas,
com azul nas ações e indicação textual dos estados. O caminho entre inscrição
e proposta aparece como sequência, sem sugerir progressão obrigatória.
Esta direção foi proposta na implementação; aprovação visual do usuário pendente.

## Colors

Tokens implementados em `src/styles/_tokens.scss`. Fundo suave, superfícies
brancas e texto azul escuro. Erro usa texto e mensagem, não somente cor.
Campos possuem borda mais contrastante que divisórias de conteúdo.

## Typography

Manrope local via Fontsource, pesos 400/600/700/800. Uma família para títulos
e conteúdo. Títulos em sentence case; textos corridos com até 70ch.
Fontes não dependem de requisição a CDN.

## Layout

Shell de até 1120px, margem de 32px em desktop e 16px em telas pequenas.
Home com apresentação à esquerda e sequência de etapas à direita;
uma coluna até 760px. Navegação pode quebrar linha; nenhuma área exige
rolagem horizontal. Conteúdo alinhado à esquerda, exceto estados vazios.

## Components

Preferir botões, links e inputs nativos. Ação principal preenchida, secundária
com borda. Altura mínima 48px nos controles e 44px nos links de navegação.
Painéis usam borda ou fundo, sem sombras decorativas. Componentes de estado
e campo estão em `src/app/shared/ui/`; controles são projetados em FormField.

## Motion

Não há animação automática. Respeitar `prefers-reduced-motion` em extensões.
Skeleton permanece estático e acompanha mensagem anunciada por leitor de tela.

## Accessibility

Foco visível, skip-link e estrutura header/nav/main/footer. Na primeira
carga, preservar o ponto inicial de navegação por teclado; em mudanças de
rota posteriores, focar o conteúdo principal. Labels e mensagens relacionados
por ID, erros com aria-invalid, mensagens de status/alerta e títulos por rota.
A revisão automática de contraste não substitui testes com leitores de tela.
