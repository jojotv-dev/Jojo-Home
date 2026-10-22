/*! Video.js | https://videojs.org/about-this-player */
import{r as e,t}from"./create-player-BPFwQVOQ.js";import{a as n,n as r,t as i}from"./constants-COmqnfpX.js";import{n as a}from"./ui-element-CUMzLga1.js";import{t as o}from"./safe-define-DXxmf7BV.js";import"./ui/container.js";import"./background-video-CEALdiBE.js";const{PlayerElement:s,PlayerController:c}=t({features:e});var l=class extends s{static{this.tagName=`background-video-player`}};o(l);var u=`background-video-player{display:contents}background-video-skin{--media-object-fit:cover;object-fit:var(--media-object-fit);width:100%;min-width:300px;height:100%;min-height:150px;display:block;position:relative}background-video-skin>:not(img,picture){object-fit:var(--media-object-fit);width:100%;height:100%;position:absolute;inset:0}background-video-skin>img,background-video-skin>picture{object-fit:var(--media-object-fit);width:100%;height:100%}
`;function d(){return`
    <media-container>
      <!-- @deprecated slot="media" is no longer required, use the default slot instead -->
      <slot name="media"></slot>
      <slot></slot>
    </media-container>
    <a rel="help" href="${r}" hidden>${i}</a>
  `}var f=class e extends a{static{this.tagName=`background-video-skin`}static{this.shadowRootOptions={mode:`open`}}static{this.getTemplateHTML=d}constructor(){super(),n(`__media-background-styles`,u),this.shadowRoot||(this.attachShadow(e.shadowRootOptions),this.shadowRoot.innerHTML=d())}};o(f);
